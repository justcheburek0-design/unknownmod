use serde::{Deserialize, Serialize};
use crate::PlayerId;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Cause { Death, CombatLogout, Restore }

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Decision {
    pub time: u64,
    pub cause: Cause,
    pub victim: PlayerId,
    pub victim_name: String,
    pub attacker: Option<PlayerId>,
    pub attacker_name: Option<String>,
    pub weapon: Option<String>,
    pub matched: bool,
    pub eliminated: bool,
}
impl Decision {
    /// Public to the victim: never includes the attacker's real name or UUID.
    pub fn explanation(&self) -> String {
        if self.cause == Cause::Restore { return "Оператор вернул тебя в игру.".into(); }
        let trigger = if self.cause == Cause::CombatLogout { "Выход во время 30-секундного боя засчитан как смерть." } else { "Засчитана смерть." };
        let result = if self.eliminated {
            "Ты выбыл: имя оружия совпало с твоим настоящим ником. Теперь ты наблюдатель."
        } else if self.matched {
            "Ты уже наблюдатель; повторного выбывания нет."
        } else if self.attacker.is_none() {
            "Выбывания нет: нет подходящего удара другого игрока."
        } else if self.weapon.is_none() {
            "Выбывания нет: у оружия нет собственного имени."
        } else {
            "Выбывания нет: имя оружия не совпало с твоим настоящим ником."
        };
        format!("{trigger} {result}")
    }
}
