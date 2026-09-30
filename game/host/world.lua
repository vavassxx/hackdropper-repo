-- Asteria Online: the authoritative world controller.
-- All character state is server-side and persisted through PlayerStore.

local players = {}
local mobs = {}
local mobByName = {}
local lastSave = {}
local lastHud = {}
local lastAction = {}
local announcements = {"Добро пожаловать в Астерию.", "В мире открыты лес, руины и подземелья.", "Исследуйте, выполняйте поручения и собирайте добычу."}

local function pid(p) return tostring(p:id()) end
local function pos(e) return e:position() end
local function dist(a, b)
  local pa, pb = pos(a), pos(b)
  local dx, dy = pa.x - pb.x, pa.y - pb.y
  return math.sqrt(dx*dx + dy*dy)
end
local function clamp(v, lo, hi) return math.max(lo, math.min(hi, v)) end

local function default_state(p)
  return {
    name = p:name(), class = "novice", level = 1, xp = 0, gold = 30,
    hp = 100, maxhp = 100, mana = 50, maxmana = 50,
    str = 5, agi = 5, int = 5, kills = 0, deaths = 0,
    inventory = {potion = 3, herb = 0, ore = 0, wolf_fang = 0},
    equipment = {weapon = "rusty_sword", armor = "traveler_cloak"},
    quests = {wolf_hunt = 0, ruins = 0},
    quest_done = {wolf_hunt = false, ruins = false}, chests = {},
    tutorial = false
  }
end

local function normalize_state(p, s)
  local d = default_state(p)
  if type(s) ~= "table" then return d end
  for k,v in pairs(d) do if s[k] == nil then s[k] = v end end
  if type(s.inventory) ~= "table" then s.inventory = d.inventory end
  if type(s.equipment) ~= "table" then s.equipment = d.equipment end
  if type(s.quests) ~= "table" then s.quests = d.quests end
  if type(s.quest_done) ~= "table" then s.quest_done = d.quest_done end
  if type(s.chests) ~= "table" then s.chests = {} end
  s.maxhp = math.max(50, tonumber(s.maxhp) or 100)
  s.maxmana = math.max(20, tonumber(s.maxmana) or 50)
  s.hp = clamp(tonumber(s.hp) or s.maxhp, 0, s.maxhp)
  s.mana = clamp(tonumber(s.mana) or s.maxmana, 0, s.maxmana)
  s.level = math.max(1, math.floor(tonumber(s.level) or 1))
  s.xp = math.max(0, math.floor(tonumber(s.xp) or 0))
  s.gold = math.max(0, math.floor(tonumber(s.gold) or 0))
  return s
end

local function save(p)
  local s = players[pid(p)]
  if s then engine.save_player(p, s); lastSave[pid(p)] = engine.tick end
end

local function notify(p, text) engine.notify(p, text) end

local function add_item(s, item, amount)
  s.inventory[item] = (tonumber(s.inventory[item]) or 0) + amount
  if s.inventory[item] < 0 then s.inventory[item] = 0 end
end

local function xp_needed(level) return 100 + (level - 1) * 75 end
local function give_xp(p, amount)
  local s = players[pid(p)]; if not s then return end
  s.xp = s.xp + amount
  while s.xp >= xp_needed(s.level) do
    s.xp = s.xp - xp_needed(s.level)
    s.level = s.level + 1
    s.maxhp = s.maxhp + 12
    s.maxmana = s.maxmana + 6
    s.str = s.str + 1; s.agi = s.agi + 1; s.int = s.int + 1
    s.hp = s.maxhp; s.mana = s.maxmana
    notify(p, "Уровень " .. s.level .. "! Характеристики восстановлены.")
  end
end

local function weapon_damage(s)
  local w = s.equipment.weapon
  if w == "iron_sword" then return 11 + s.str * 1.4 end
  if w == "oak_bow" then return 9 + s.agi * 1.5 end
  if w == "apprentice_staff" then return 8 + s.int * 1.7 end
  return 7 + s.str * 1.2
end

local function attack_range(s)
  if s.class == "ranger" then return 4.5 end
  if s.class == "arcanist" then return 3.8 end
  return 1.8
end

local function find_target(p, range)
  local best, bestd
  for _,e in ipairs(world.all()) do
    local n = e:name()
    if n:sub(1,4) == "mob:" and e:exists() then
      local m = mobs[n]
      if m and m.alive then
        local d = dist(p,e)
        if d <= range and (not bestd or d < bestd) then best, bestd = e, d end
      end
    end
  end
  return best, bestd
end

local function kill_mob(p, e)
  local n = e:name(); local m = mobs[n]
  if not m or not m.alive then return end
  m.alive = false; m.respawn = engine.tick + m.respawnTicks
  m.hp = m.maxhp
  e.clear_collider(); e.set_scale(0.01); e.set_position(-100,-100,0)
  local s = players[pid(p)]
  s.kills = s.kills + 1
  if n:find("wolf") then s.quests.wolf_hunt = s.quests.wolf_hunt + 1; add_item(s,"wolf_fang",1); notify(p,"Волк повержен. Получен клык.") end
  if n:find("skeleton") then add_item(s,"ore",1) end
  if n:find("slime") then add_item(s,"herb",1) end
  local gold = math.random(m.goldMin,m.goldMax); s.gold = s.gold + gold
  give_xp(p,m.xp)
  notify(p, "Добыча: "..gold.." золота.")
end

local function player_attack(p)
  local s = players[pid(p)]; if not s or s.hp <= 0 or s.class == "novice" then return end
  local tick = engine.tick
  if lastAction[pid(p)] and tick - lastAction[pid(p)] < 5 then return end
  lastAction[pid(p)] = tick
  local target = find_target(p, attack_range(s))
  if not target then notify(p,"Нет цели в радиусе атаки."); return end
  local n = target:name(); local m = mobs[n]
  local damage = math.floor(weapon_damage(s) + math.random(0, math.max(1, s.level*2)))
  if s.class == "ranger" then damage = damage + 2 end
  if s.class == "arcanist" then damage = damage + 3 end
  m.hp = m.hp - damage
  notify(p, "Удар по "..n:gsub("mob:","")..": "..damage.." урона.")
  if m.hp <= 0 then kill_mob(p,target) end
end

local function class_pick(p, class)
  local s = players[pid(p)]; if not s or s.class ~= "novice" then return end
  local cfg = {
    guardian={hp=125,mana=35,str=8,agi=4,int=3,weapon="rusty_sword",label="Страж"},
    ranger={hp=95,mana=55,str=4,agi=8,int=4,weapon="oak_bow",label="Следопыт"},
    arcanist={hp=80,mana=90,str=3,agi=4,int=9,weapon="apprentice_staff",label="Арканист"}
  }
  local c = cfg[class]; if not c then return end
  s.class=class; s.maxhp=c.hp; s.hp=c.hp; s.maxmana=c.mana; s.mana=c.mana
  s.str=c.str; s.agi=c.agi; s.int=c.int; s.equipment.weapon=c.weapon
  s.tutorial=true
  notify(p,"Класс выбран: "..c.label..". Двигайтесь на север — там тракт к лесу.")
end

local function hud(p)
  local s = players[pid(p)]; if not s then return end
  local hp = math.max(0,s.hp); local mp = math.max(0,s.mana)
  local strings = {"АСТЕРИЯ", p:name(), "Lv "..s.level.."  XP "..s.xp.."/"..xp_needed(s.level),
                   "HP "..hp.."/"..s.maxhp, "MP "..mp.."/"..s.maxmana,
                   "Золото: "..s.gold, "Класс: "..(s.class=="guardian" and "Страж" or s.class=="ranger" and "Следопыт" or s.class=="arcanist" and "Арканист" or "Новичок")}
  local widgets = {
    {type="panel",x=.02,y=.02,w=.29,h=.20,bg={.04,.06,.10,.86}},
    {type="text",x=.035,y=.035,ref=1,size=20,color={1,1,1}},
    {type="text",x=.035,y=.073,ref=2,size=13,color={.8,.86,1}},
    {type="bar",x=.035,y=.105,w=.25,h=.018,value=hp,max=s.maxhp,fill={.82,.18,.18},back={.20,.08,.08}},
    {type="text",x=.035,y=.128,ref=4,size=11,color={1,1,1}},
    {type="bar",x=.035,y=.151,w=.25,h=.014,value=mp,max=s.maxmana,fill={.25,.45,.95},back={.08,.10,.20}},
    {type="text",x=.035,y=.171,ref=5,size=10,color={.8,.9,1}},
    {type="button",x=.82,y=.03,w=.15,h=.055,ref=6,cmd=100,value="inventory"},
    {type="button",x=.82,y=.095,w=.15,h=.055,ref=7,cmd=101,value="quests"},
    {type="text",x=.72,y=.88,ref=3,size=11,color={1,1,1}},
  }
  if s.class == "novice" then
    widgets[#widgets+1]={type="panel",x=.20,y=.30,w=.60,h=.34,bg={.03,.04,.07,.95}}
    widgets[#widgets+1]={type="text",x=.25,y=.33,ref=1,size=24,color={1,.9,.55}}
    strings[#strings+1]="Выберите путь"
    local r=#strings; strings[r+1]="Страж — ближний бой и выживаемость"; strings[r+1]="Следопыт — дальняя атака и скорость"; strings[r+1]="Арканист — мощная магия"
    widgets[#widgets+1]={type="text",x=.25,y=.39,ref=r,size=13,color={.85,.88,1}}
    widgets[#widgets+1]={type="button",x=.25,y=.45,w=.15,h=.06,ref=r+1,cmd=110,value="guardian"}
    widgets[#widgets+1]={type="button",x=.42,y=.45,w=.15,h=.06,ref=r+2,cmd=110,value="ranger"}
    widgets[#widgets+1]={type="button",x=.59,y=.45,w=.15,h=.06,ref=r+3,cmd=110,value="arcanist"}
  elseif s.hp <= 0 then
    strings[#strings+1]="Вы погибли. Возвращение в Астерию бесплатно."
    local r=#strings
    widgets[#widgets+1]={type="panel",x=.28,y=.42,w=.44,h=.15,bg={.08,.03,.03,.95}}
    widgets[#widgets+1]={type="text",x=.32,y=.45,ref=r,size=15,color={1,.5,.5}}
    widgets[#widgets+1]={type="button",x=.42,y=.49,w=.18,h=.05,ref=6,cmd=120,value="respawn"}
  end
  engine.layout(p,widgets,strings)
end

local function inventory_ui(p)
  local s=players[pid(p)]; if not s then return end
  local strings={"ИНВЕНТАРЬ","Зелье здоровья x"..(s.inventory.potion or 0),"Трава x"..(s.inventory.herb or 0),"Руда x"..(s.inventory.ore or 0),"Клык волка x"..(s.inventory.wolf_fang or 0),"Оружие: "..s.equipment.weapon,"Закрыть"}
  local w={{type="panel",x=.22,y=.18,w=.56,h=.58,bg={.03,.04,.07,.96}},{type="text",x=.27,y=.22,ref=1,size=22,color={1,.9,.55}},
    {type="text",x=.28,y=.31,ref=2,size=14,color={1,1,1}},{type="button",x=.57,y=.30,w=.16,h=.055,ref=2,cmd=102,value="potion"},
    {type="text",x=.28,y=.39,ref=3,size=14,color={.75,.95,.75}}, {type="text",x=.28,y=.46,ref=4,size=14,color={.8,.8,1}},
    {type="text",x=.28,y=.53,ref=5,size=14,color={1,.75,.65}}, {type="text",x=.28,y=.60,ref=6,size=13,color={.8,.86,1}},
    {type="button",x=.55,y=.66,w=.16,h=.055,ref=7,cmd=103,value="close"}}
  engine.layout(p,w,strings)
end

local function quest_ui(p)
  local s=players[pid(p)]; if not s then return end
  local strings={"ЗАДАНИЯ","Охота на волков: "..s.quests.wolf_hunt.."/5","Вернитесь к Смотрителю у северных ворот.","Руины: исследование — отправляйтесь к восточному храму.","Закрыть"}
  local w={{type="panel",x=.20,y=.22,w=.60,h=.48,bg={.03,.04,.07,.96}},{type="text",x=.25,y=.26,ref=1,size=22,color={1,.9,.55}},
    {type="text",x=.25,y=.35,ref=2,size=14,color={1,1,1}},{type="text",x=.25,y=.41,ref=3,size=12,color={.75,.8,.9}},
    {type="text",x=.25,y=.50,ref=4,size=14,color={1,1,1}},{type="button",x=.52,y=.61,w=.18,h=.055,ref=5,cmd=103,value="close"}}
  engine.layout(p,w,strings)
end

function open_shop(p)
  local strings={"ТОРГОВЕЦ","Зелье здоровья — 8 золота","Купить","Закрыть"}
  local w={{type="panel",x=.28,y=.28,w=.44,h=.30,bg={.05,.04,.02,.96}},{type="text",x=.33,y=.33,ref=1,size=22,color={1,.85,.45}},
    {type="text",x=.33,y=.42,ref=2,size=13,color={1,1,1}},{type="button",x=.50,y=.48,w=.15,h=.055,ref=3,cmd=130,value="potion"},{type="button",x=.50,y=.56,w=.15,h=.055,ref=4,cmd=103,value="close"}}
  engine.layout(p,w,strings)
end

local function choose_command(p, code, arg)
  local s=players[pid(p)]; if not s then return end
  if code==110 then class_pick(p,arg); hud(p); return end
  if code==100 then inventory_ui(p); return end
  if code==101 then quest_ui(p); return end
  if code==102 and arg=="potion" then
    if (s.inventory.potion or 0)>0 and s.hp<s.maxhp then s.inventory.potion=s.inventory.potion-1; s.hp=math.min(s.maxhp,s.hp+45); notify(p,"Зелье восстановило здоровье.") end
    inventory_ui(p); return
  end
  if code==131 and arg=="heal" then
    s.hp=s.maxhp; s.mana=s.maxmana; notify(p,"Целитель восстановил вас полностью."); hud(p); return
  end
  if code==132 and arg=="inn" then
    if s.gold>=12 then s.gold=s.gold-12; s.hp=s.maxhp; s.mana=s.maxmana; notify(p,"Вы хорошо отдохнули.") else notify(p,"Недостаточно золота.") end
    hud(p); return
  end
  if code==133 and arg=="upgrade" then
    if (s.inventory.ore or 0)>=10 and s.equipment.weapon=="rusty_sword" then s.inventory.ore=s.inventory.ore-10; s.equipment.weapon="iron_sword"; notify(p,"Кузнец выковал железный меч!") else notify(p,"Нужно 10 руды и ржавый меч.") end
    hud(p); return
  end
  if code==140 and (arg=="forest_chest" or arg=="ruins_chest") then
    if s.chests[arg] then notify(p,"Этот сундук уже пуст для вас."); hud(p); return end
    s.chests[arg]=true
    if arg=="forest_chest" then add_item(s,"potion",2); s.gold=s.gold+15; notify(p,"Сундук: 2 зелья и 15 золота.")
    else add_item(s,"ore",4); s.gold=s.gold+40; give_xp(p,35); notify(p,"Сундук руин: руда, золото и опыт.") end
    hud(p); return
  end
  if code==130 and arg=="potion" then
    if s.gold>=8 then s.gold=s.gold-8; add_item(s,"potion",1); notify(p,"Зелье куплено.") else notify(p,"Недостаточно золота.") end
    open_shop(p); return
  end
  if code==103 then hud(p); return end
  if code==120 and arg=="respawn" then
    if s.hp<=0 then s.deaths=s.deaths+1; s.hp=s.maxhp; s.mana=s.maxmana; p.set_position(8,8,0); notify(p,"Вы вернулись к фонтану.") end
    hud(p); return
  end
end

engine.on_command(choose_command)

engine.on_action(function(p, action)
  local s=players[pid(p)]; if not s then return end
  if action=="primary" then player_attack(p); return end
  if action=="inventory" then inventory_ui(p); return end
  if action=="secondary" then
    if s.class=="guardian" and s.mana>=10 then s.mana=s.mana-10; s.hp=math.min(s.maxhp,s.hp+30); notify(p,"Щитовой импульс: +30 HP.")
    elseif s.class=="ranger" and s.mana>=8 then s.mana=s.mana-8; local q=world.get_near(pos(p).x,pos(p).y,1.5); for _,e in ipairs(q) do if e:name():sub(1,4)=="mob:" and mobs[e:name()] and mobs[e:name()].alive then mobs[e:name()].hp=mobs[e:name()].hp-20 end end; notify(p,"Круговой залп.")
    elseif s.class=="arcanist" and s.mana>=14 then s.mana=s.mana-14; local target=find_target(p,5.5); if target then local m=mobs[target:name()]; m.hp=m.hp-32; notify(p,"Огненная вспышка: 32 урона."); if m.hp<=0 then kill_mob(p,target) end else notify(p,"Нет цели для заклинания.") end
    else notify(p,"Недостаточно маны или класс не выбран.") end
    return
  end
end)

engine.on_disconnect(function(p)
  local k=pid(p); save(p); players[k]=nil; mobs[k]=mobs[k]; lastSave[k]=nil; lastHud[k]=nil; lastAction[k]=nil
end)

engine.on_tick(function(tick)
  -- Discover/recover players. This is also the connection hook for the script layer.
  for _,p in ipairs(world.players()) do
    local k=pid(p)
    if not players[k] then
      local loaded=engine.load_player(p)
      players[k]=normalize_state(p,loaded)
      notify(p, announcements[1 + (p:id() % #announcements)])
      hud(p)
    end
    local s=players[k]
    if s.hp>0 then
      if tick % 40 == 0 then s.hp=math.min(s.maxhp,s.hp+1); s.mana=math.min(s.maxmana,s.mana+2) end
    end
    if tick - (lastHud[k] or -999) >= 10 then hud(p); lastHud[k]=tick end
    if tick - (lastSave[k] or 0) >= 200 then save(p) end
  end

  -- Quest progression is per character, never global.
  for _,p in ipairs(world.players()) do
    local s=players[pid(p)]
    if s then
      if s.quests.wolf_hunt>=5 and not s.quest_done.wolf_hunt then
        s.quest_done.wolf_hunt=true; s.gold=s.gold+80; give_xp(p,120); notify(p,"Поручение волков завершено: 80 золота и 120 XP.")
      end
      local pp=pos(p)
      if not s.quest_done.ruins and pp.x>=44 and pp.x<=50 and pp.y>=44 and pp.y<=50 then
        s.quest_done.ruins=true; s.gold=s.gold+120; give_xp(p,180); notify(p,"Тайна руин раскрыта: 120 золота и 180 XP.")
      end
    end
  end

  -- Initialize and respawn world mobs. Map entities are persistent; death only hides them.
  for _,e in ipairs(world.all()) do
    local n=e:name()
    if n:sub(1,4)=="mob:" and not mobs[n] then
      local kind=n:sub(5)
      local hp=kind:find("skeleton") and 120 or kind:find("wolf") and 75 or 55
      mobs[n]={hp=hp,maxhp=hp,alive=true,respawnTicks=600,xp=kind:find("skeleton") and 65 or 35,goldMin=3,goldMax=10,respawn=0}
      mobByName[n]=e
    end
  end
  for n,m in pairs(mobs) do
    local e=mobByName[n]
    if e and e:exists() and not m.alive and tick>=m.respawn then
      m.alive=true; m.hp=m.maxhp; e.set_scale(1.0); e.set_collider_circle(.32)
      local x,y = n:find("wolf") and 25 or n:find("skeleton") and 47 or 31, n:find("wolf") and 24 or n:find("skeleton") and 44 or 30
      e.set_position(x + math.random(-3,3), y + math.random(-3,3), 0)
    end
    if e and e:exists() and m.alive and tick % 8 == 0 then
      local target, d = nil, 999
      for _,p in ipairs(world.players()) do if players[pid(p)] and players[pid(p)].hp>0 then local dd=dist(e,p); if dd<d then target,d=p,dd end end end
      if target and d<7 then
        local ep=pos(e); local tp=pos(target); local dx,dy=tp.x-ep.x,tp.y-ep.y; local len=math.sqrt(dx*dx+dy*dy)
        if len>1.1 then e.move((dx/len)*.28,(dy/len)*.28) elseif tick%20==0 then local s=players[pid(target)]; s.hp=s.hp-4; notify(target,""..n:gsub("mob:","").." наносит 4 урона.") end
      end
    end
  end
end)
