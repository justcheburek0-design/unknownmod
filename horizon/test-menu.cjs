const t=require('./test-unknown.cjs'),fs=require('node:fs'),path=require('node:path');
const checks=[];
function check(name,ok){if(!ok)throw Error('FAIL '+name);checks.push(name);console.log('PASS '+name)}
async function request(bot,value){await t.sleep(350);const n=bot.menus.length;bot.menuRequest(value);await t.until(()=>bot.menus.length>n,'menu response');return bot.menus.at(-1)}
async function main(){
 const a=await t.connect('MenuOperator');let b=await t.connect('MenuPlayer');
 t.cmd('op MenuOperator');t.cmd('deop MenuPlayer');t.cmd('unknown reveal cancel');t.cmd('unknown restore MenuPlayer');
 t.cmd('difficulty easy');t.cmd('gamemode survival MenuPlayer');t.cmd('gamerule minecraft:natural_health_regeneration false');
 for(const bot of [a,b]){if(bot.health<=0){bot.respawn();await t.until(()=>bot.health>0,'initial respawn')}}
 const basic=await request(b,{action:'status'});
 check('ordinary menu has own status and timers',typeof basic.status==='string'&&typeof basic.combat_seconds==='number');
 check('ordinary menu has no admin identities or private audit',!('admin'in basic)&&!('audit'in basic));
 const denied=await request(b,{action:'reveal',target:a.uuid});
 check('forged non-op action rejected by server',denied.message.includes('оператор')&&!t.state().reveals[BigInt('0x'+a.uuid).toString()]);
 const deniedSetting=await request(b,{action:'setting',field:'duration',value:1});
 check('non-op cannot modify settings',deniedSetting.message.includes('оператор'));
 const admin=await request(a,{action:'status'});
 check('operator receives controls and private audit',admin.admin.players.some(p=>p.id===b.uuid)&&Array.isArray(admin.audit));
 const invalid=await request(a,{action:'setting',field:'duration',value:0});check('invalid setting rejected',invalid.message.includes('диапазон'));
 const revealed=await request(a,{action:'reveal',target:b.uuid});check('menu reveals selected UUID',revealed.message==='Игрок раскрыт.');
 const hidden=await request(a,{action:'hide',target:b.uuid});check('menu restores anonymity',hidden.message==='Анонимность восстановлена.');
 t.cmd('tp MenuOperator 0 -60 0');t.cmd('tp MenuPlayer 1 -60 0');
 t.cmd('item replace entity MenuOperator weapon.mainhand with minecraft:stick[minecraft:custom_name={text:"MenuPlayer"}]');
 t.cmd('effect give MenuPlayer minecraft:instant_health 1 10 true');await t.sleep(1300);
 t.cmd('damage MenuPlayer 19 minecraft:generic');await t.sleep(650);a.attack(b);await t.until(()=>b.health<=0,'named death');
 b.respawn();await t.until(()=>b.health>0,'spectator respawn');
 const eliminated=await request(b,{action:'status'});
 check('victim sees reason without hidden attacker nickname',eliminated.last_decision.includes('выбыл')&&!eliminated.last_decision.includes('MenuOperator'));
 const journal=await request(a,{action:'status'});
 check('journal captures attacker weapon and decision',journal.audit.some(e=>e.victim_name==='MenuPlayer'&&e.attacker_name==='MenuOperator'&&e.weapon.includes('MenuPlayer')&&e.matched&&e.eliminated));
 const restored=await request(a,{action:'restore',target:b.uuid});check('operator can restore through menu',restored.message.includes('возвращён'));
 const after=await request(a,{action:'status'});check('restoration recorded in private journal',after.audit.some(e=>e.victim_name==='MenuPlayer'&&e.cause==='restore'));
 t.cmd('unknown ghost MenuPlayer');await t.until(()=>b.profiles.get(b.uuid)?.mode===3,'spectator before offline restore');
 b.disconnect();await t.until(()=>b.closed,'offline target');
 const offline=await request(a,{action:'restore',target:b.uuid});check('offline player can be restored through menu',offline.message.includes('возвращён'));
 b=await t.connect('MenuPlayer');await t.until(()=>b.profiles.get(b.uuid)?.mode===0,'restored mode on reconnect');
 check('offline restore survives reconnect without spectator trap',!t.player('MenuPlayer').ghost&&b.profiles.get(b.uuid)?.mode===0);
 fs.writeFileSync(path.join(__dirname,'../build/horizon/menu-test-results.json'),JSON.stringify({passed:checks.length,checks,at:new Date().toISOString()},null,2));
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{for(const b of t.bots.values())b.socket.destroy()});
