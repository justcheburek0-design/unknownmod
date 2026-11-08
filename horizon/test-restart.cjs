// Run "prepare", restart the isolated server, then run "verify".
const t=require('./test-unknown.cjs'),fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),dir=path.join(root,'build/horizon/unknown-test-server');
const supervisor=path.join(root,'build/horizon/runtime/horizon-windows-x64/horizon-server.exe');
const record=path.join(root,'build/horizon/unknown-restart-results.json');
const winner=()=>JSON.parse(fs.readFileSync(path.join(dir,'test-world/unknownmod/winner.json'),'utf8'));
const reveal=()=>Object.values(t.state().reveals)[0]?.Paused?.remaining;
function check(name,condition){if(!condition)throw Error('FAIL '+name);console.log('PASS '+name)}
async function main(){
 const a=await t.connect('TestAttacker'),b=await t.connect('TestTarget');
 if(process.argv.includes('prepare')){
  t.cmd('unknown reveal cancel');
  for(const bot of [a,b]){t.cmd('unknown restore '+bot.name);if(bot.health<=0){bot.respawn();await t.until(()=>bot.health>0,'respawn')}t.cmd('gamemode survival '+bot.name);t.cmd('effect give '+bot.name+' minecraft:instant_health 1 10 true')}
  t.cmd('unknown restore TestObserver');t.cmd('unknown ghost TestObserver');
  t.cmd('tp TestAttacker 0.5 -60 0.5');t.cmd('tp TestTarget 1.5 -60 0.5');
  t.cmd('item replace entity TestAttacker weapon.mainhand with minecraft:stick[minecraft:custom_name={text:"TestTarget"}]');
  t.cmd('unknown reveal TestTarget 1');await t.sleep(1500);a.attack(b);await t.until(()=>b.health<20,'PvP accepted');
  execFileSync(supervisor,['stop','--dir',dir],{encoding:'utf8',windowsHide:true,timeout:30000});
  check('graceful shutdown during named PvP does not eliminate',!t.player('TestTarget').ghost);
  check('graceful shutdown preserves eliminated spectators',t.player('TestObserver').ghost);
  check('hunt saved as paused remaining time',reveal()>50000&&reveal()<=60000);
  fs.writeFileSync(record,JSON.stringify({prepared:new Date().toISOString(),remaining:reveal(),winner:winner(),checks:['shutdown_safe','ghost_saved','hunt_paused']},null,2));
 }else{
  const before=JSON.parse(fs.readFileSync(record,'utf8'));const observer=await t.connect('TestObserver');
  await t.until(()=>observer.profiles.get(observer.uuid)?.mode===3,'spectator restored');
  check('spectator survives full server restart',t.player('TestObserver').ghost&&observer.profiles.get(observer.uuid).mode===3);
  check('shutdown did not kill the tagged victim',b.health>0&&!t.player('TestTarget').ghost);
  check('offline server time did not consume the hunt',reveal()>before.remaining-6000&&reveal()<=before.remaining);
  check('restarted hunt restores the revealed profile',b.profiles.get(b.uuid)?.name==='TestTarget'&&!b.profiles.get(b.uuid)?.properties.unknownmod_anonymous);
  check('dragon victory survives full server restart',winner()===before.winner&&winner()==='QAWin');
  const cfg=JSON.parse(fs.readFileSync(path.join(dir,'test-world/unknownmod/config.json'),'utf8'));
  check('90 minutes / 3 hours / minimum 3 persist',cfg.reveal_duration_minutes===90&&cfg.reveal_interval_hours===3&&cfg.minimum_players===3);
  before.verified=new Date().toISOString();before.checks.push('ghost_restart','shutdown_no_death','offline_pause','profile_restart','winner_restart','config_restart');fs.writeFileSync(record,JSON.stringify(before,null,2));
 }
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{for(const b of t.bots.values())b.socket.destroy()});
