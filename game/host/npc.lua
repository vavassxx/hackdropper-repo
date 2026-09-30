local kind = entity:name()
entity.set_trigger(1.8)

entity.on_interact(function(p)
  if kind == "npc:warden" then
    engine.dialog(p, "Смотритель: в лесу развелись волки. Принесите пять клыков — награда будет достойной.", {"Принять поручение", "Позже"}, function(choice)
      if choice==1 then engine.notify(p,"Охота начата. Пять волчьих клыков.") end
    end)
  elseif kind == "npc:merchant" then
    engine.dialog(p, "Торговец: зелья по восемь золотых. Покупать будете?", {"Открыть лавку", "Нет"}, function(choice)
      if choice==1 then open_shop(p) end
    end)
  elseif kind == "npc:healer" then
    engine.dialog(p,"Целитель: восстановить здоровье и ману?", {"Да", "Нет"}, function(choice)
      if choice==1 then engine.layout(p, {{type="button",x=.40,y=.44,w=.20,h=.06,ref=1,cmd=131,value="heal"}}, {"Восстановить"}) end
    end)
  elseif kind == "npc:smith" then
    engine.dialog(p,"Кузнец: 10 руды превратят ржавый меч в железный.", {"Улучшить оружие", "Уйти"}, function(choice)
      if choice==1 then engine.layout(p, {{type="button",x=.40,y=.44,w=.20,h=.06,ref=1,cmd=133,value="upgrade"}}, {"Перековать"}) end
    end)
  elseif kind == "npc:inn" then
    engine.dialog(p,"Трактирщица: комната стоит 12 золотых и полностью восстановит вас.", {"Снять комнату", "Нет"}, function(choice)
      if choice==1 then engine.layout(p, {{type="button",x=.40,y=.44,w=.20,h=.06,ref=1,cmd=132,value="inn"}}, {"Отдохнуть"}) end
    end)
  elseif kind == "portal:ruins" then
    local s=p:position(); p.set_position(44,44,0); engine.notify(p,"Вы вошли в Восточные руины.")
  elseif kind == "portal:town" then
    p.set_position(8,8,0); engine.notify(p,"Вы вернулись в Астерию.")
  elseif kind == "npc:guide" then
    engine.dialog(p,"Путеводитель: WASD — движение, J — атака, K — умение, L — взаимодействие, I — инвентарь.", {"Понятно"}, function() end)
  elseif kind == "chest:forest" or kind == "chest:ruins" then
    local loot = kind == "chest:forest" and "forest_chest" or "ruins_chest"
    engine.dialog(p,"Сундук заперт не был. Открыть?", {"Открыть", "Оставить"}, function(choice)
      if choice==1 then engine.layout(p, {{type="button",x=.40,y=.44,w=.20,h=.06,ref=1,cmd=140,value=loot}}, {"Забрать добычу"}) end
    end)
  elseif kind == "npc:quest" then
    engine.dialog(p,"Архивист: в руинах спрятаны древние записи. Исследуйте храм на востоке и возвращайтесь.", {"Отправиться", "Позже"}, function(choice)
      if choice==1 then engine.notify(p,"Руины отмечены на востоке карты.") end
    end)
  end
end)
