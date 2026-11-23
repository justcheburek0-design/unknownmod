//! A signed Mojang texture payload contains account identifiers as well as URLs.
//! Only visual fields may cross the wire for somebody else's revealed profile.
use base64::{Engine, engine::general_purpose::{STANDARD, STANDARD_NO_PAD}};
use serde_json::{Value, json};

pub fn public_textures(value: &str) -> Option<String> {
    let bytes = STANDARD.decode(value).or_else(|_| STANDARD_NO_PAD.decode(value)).ok()?;
    let decoded: Value = serde_json::from_slice(&bytes).ok()?;
    let textures = decoded.get("textures")?.as_object()?;
    let mut visual = serde_json::Map::new();
    for kind in ["SKIN", "CAPE", "ELYTRA"] {
        let Some(texture) = textures.get(kind) else { continue; };
        let url = texture.get("url")?.as_str()?;
        // Same domains accepted by the existing Horizon 6.1 skin loader.
        if !["http://textures.minecraft.net/texture/", "https://textures.minecraft.net/texture/"]
            .iter().any(|prefix| url.starts_with(prefix)) { return None; }
        let mut clean = json!({"url": url});
        if kind == "SKIN" && texture.pointer("/metadata/model").and_then(Value::as_str) == Some("slim") {
            clean["metadata"] = json!({"model": "slim"});
        }
        visual.insert(kind.into(), clean);
    }
    Some(STANDARD.encode(serde_json::to_vec(&json!({"textures": visual})).ok()?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_visuals_without_account_metadata() {
        let source = json!({"profileId":"real-uuid", "profileName":"Alice", "signatureRequired":true,
            "textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/abc", "metadata":{"model":"slim", "account":"Alice"}},
                        "CAPE":{"url":"http://textures.minecraft.net/texture/def"}, "account":"real-uuid"}});
        let public = public_textures(&STANDARD.encode(source.to_string())).unwrap();
        let clean: Value = serde_json::from_slice(&STANDARD.decode(&public).unwrap()).unwrap();
        assert_eq!(clean, json!({"textures":{
            "SKIN":{"url":"https://textures.minecraft.net/texture/abc", "metadata":{"model":"slim"}},
            "CAPE":{"url":"http://textures.minecraft.net/texture/def"}}}));
        assert_eq!(public_textures(&public), Some(public));
    }

    #[test]
    fn malformed_or_external_textures_are_not_forwarded() {
        for value in ["not base64".into(), STANDARD.encode("{}"),
            STANDARD.encode(r#"{"textures":{"SKIN":{"url":"https://example.org/Alice"}}}"#)] {
            assert!(public_textures(&value).is_none());
        }
    }
}
