const t=require('./test-unknown.cjs'),fs=require('node:fs'),path=require('node:path');
const checks=[],skipped=[];function check(name,ok){if(!ok)throw Error('FAIL '+name);checks.push(name);console.log('PASS '+name)}
const resultFile=path.join(__dirname,'../build/horizon/alias-test-results.json');
const previous=process.env.UNKNOWN_EXPECT_NEW_ALIASES==='1'?JSON.parse(fs.readFileSync(resultFile,'utf8')):null;
async function main(){
 let a=await t.connect('AliasA');const b=await t.connect('AliasB'),c=await t.connect('AliasObserver');
 t.cmd('difficulty peaceful');t.cmd('gamerule minecraft:spawn_mobs false');t.cmd('unknown reveal cancel');
 for(const bot of [a,b,c]){t.cmd('unknown restore '+bot.name);t.cmd('gamemode survival '+bot.name);if(bot.health<=0){bot.respawn();await t.until(()=>bot.health>0,'initial respawn')}}
 t.cmd('tp AliasA 0 -60 0');t.cmd('tp AliasB 4 -60 0');t.cmd('tp AliasObserver 8 -60 0');await t.sleep(1500);
 await t.until(()=>c.seenUuid(a)&&b.seenUuid(a)&&c.profileOf(a),'public entity/profile');
 const alias=c.seenUuid(a);
 if(previous){check('server restart rotates public UUIDs',Boolean(previous.public_uuid)&&previous.public_uuid!==alias);}
 check('entity UUID differs from real offline/account UUID',alias!==a.uuid);
 check('all viewers use the same public UUID',b.seenUuid(a)===alias);
 check('profile UUID agrees with entity UUID',c.profiles.has(alias));
 check('real account UUID was never sent in other player profiles',!c.profileUuids.has(a.uuid)&&!b.profileUuids.has(a.uuid));
 check('owner keeps its own profile and skin channel',a.profiles.has(a.uuid)&&!a.profiles.get(a.uuid).properties.unknownmod_anonymous);
 t.cmd('unknown reveal AliasA 1');await t.until(()=>c.profileOf(a)?.name==='AliasA','reveal');
 check('revealing changes name/skin without changing public UUID',c.seenUuid(a)===alias&&!c.profileOf(a).properties.unknownmod_anonymous);
 // Offline test names may not have Mojang accounts, and the service may rate-limit.
 // Deterministic signed-payload coverage is provided by test-own-skin.ps1.
 await t.sleep(1500);
 if(a.profiles.get(a.uuid)?.properties.textures){
  await t.until(()=>c.profileOf(a)?.properties.textures,'revealed skin');
  const ownTextures=JSON.parse(Buffer.from(a.profiles.get(a.uuid).properties.textures,'base64').toString());
  const publicTextures=JSON.parse(Buffer.from(c.profileOf(a).properties.textures,'base64').toString());
  check('reveal retains original skin URLs',require('node:util').isDeepStrictEqual(publicTextures.textures,ownTextures.textures));
  check('revealed skin contains no account metadata or original signature',!('profileId'in publicTextures)&&!('profileName'in publicTextures)&&!c.profileOf(a).signatures.textures);
 }else{
  if(process.env.UNKNOWN_REQUIRE_FETCHED_SKIN==='1')throw Error('Mojang did not supply an original skin');
  skipped.push('live Mojang skin comparison: no texture supplied for offline test account');
  console.log('SKIP '+skipped.at(-1));
 }
 t.cmd('unknown hide AliasA');await t.until(()=>c.profileOf(a)?.properties.unknownmod_anonymous==='1','hide');
 check('hiding keeps public UUID and removes original textures',c.seenUuid(a)===alias&&!c.profileOf(a).properties.textures);
 check('vanilla operator commands still resolve real accounts',t.cmd('give AliasA minecraft:apple').includes('AliasA'));
 a.disconnect();await t.until(()=>a.closed&&c.removedProfiles.includes(alias),'alias removed');
 check('disconnect removes public UUID without leaking account UUID',!c.removedProfiles.includes(a.uuid));
 a=await t.connect('AliasA');await t.until(()=>c.seenUuid(a)&&c.profileOf(a),'rejoin visible');
 check('reconnect reuses the public UUID',c.seenUuid(a)===alias);
 t.cmd('unknown ghost AliasObserver');await t.until(()=>c.profiles.get(c.uuid)?.mode===3,'spectator');
 t.cmd('tp AliasA 5 -60 0');t.cmd('tp AliasObserver 32 -60 0');await t.until(()=>Math.abs(c.position[0]-32.5)<1,'spectator position');
 c.teleportTo(a.uuid);await t.sleep(700);
 check('guessed real UUID is rejected for spectator teleport',Math.abs(c.position[0]-32.5)<1);
 c.teleportTo(alias);await t.until(()=>Math.abs(c.position[0]-a.position[0])<1.5,'alias teleport');
 check('public UUID spectator teleport reaches the correct player',true);
 fs.writeFileSync(resultFile,JSON.stringify({passed:checks.length,checks,skipped,public_uuid:alias,at:new Date().toISOString()},null,2));
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{for(const b of t.bots.values())b.socket.destroy()});
