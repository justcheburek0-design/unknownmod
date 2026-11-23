// prepare-clean / prepare-crash, start (or wait for supervisor), then verify.
// Uses only the isolated server in build/horizon/unknown-test-server.
const t=require('./test-unknown.cjs'),fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),dir=path.join(root,'build/horizon/unknown-test-server');
const supervisor=path.join(root,'build/horizon/runtime/horizon-windows-x64/horizon-server.exe');
const eventFile=path.join(dir,'test-world/unknownmod/events.json'),record=path.join(root,'build/horizon/event-restart-results.json');
const board=()=>JSON.parse(fs.readFileSync(eventFile,'utf8'));
const playerId=b=>BigInt('0x'+b.uuid).toString();
function check(name,ok){if(!ok)throw Error('FAIL '+name);console.log('PASS '+name);}
async function command(bot,text,needle){const n=bot.events.length;bot.command(text);await t.until(()=>bot.events.slice(n).some(e=>e.text.includes(needle)),text);}
async function tp(bot,x,z){t.cmd(`tp ${bot.name} ${x} -60 ${z}`);await t.until(()=>Math.abs(bot.position[0]-x)<=.6&&Math.abs(bot.position[2]-z)<=.6,'teleport');await t.sleep(400);}
async function main(){
 const mode=process.argv[2]||'verify';
 const a=await t.connect('EventResumeA');
 if(mode.startsWith('prepare-')){
  const b=await t.connect('EventResumeB');t.cmd('unknown event cancel');t.cmd('unknown start');
  for(const p of [a,b]){t.cmd('unknown restore '+p.name);if(p.health<=0){p.respawn();await t.until(()=>p.health>0,'respawn');}t.cmd('gamemode survival '+p.name);}
  const cooperative=mode==='prepare-clean';
  await tp(a,-8,0);await tp(b,cooperative?8:0,cooperative?0:12);
  t.cmd(`unknown event prepare beacon ${cooperative?'coop':'battle'} 0 -60 0`);t.cmd('unknown event open');
  await command(a,'event join','Ты участвуешь');await command(b,'event join','Ты участвуешь');t.cmd('unknown event start');
  if(cooperative){await command(a,'event interact','Компонент установлен');await command(b,'event interact','Компонент установлен');}
  else {await command(a,'event interact','Блок у тебя');await tp(a,0,0);}
  await t.until(()=>board().current.progress_ms>=3000,'charge before shutdown');
  if(mode==='prepare-clean')execFileSync(supervisor,['stop','--dir',dir],{windowsHide:true,timeout:30000});
  else if(mode==='prepare-crash'){
   const script="$target = @(Get-CimInstance Win32_Process -Filter \"Name='horizon-client.exe'\" | Where-Object { $_.CommandLine -like '*unknown-test-server*' }); if ($target.Count -ne 1) { throw 'Expected exactly one isolated test engine' }; Stop-Process -Id $target[0].ProcessId -Force";
   execFileSync('powershell.exe',['-NoProfile','-Command',script],{windowsHide:true,timeout:15000});
  }else throw Error('mode: prepare-clean, prepare-crash, verify');
  const saved=board();check('running encounter persisted through shutdown',saved.current.phase==='Running'&&saved.current.progress_ms>=3000&&(cooperative?saved.current.components.length===2:saved.current.carrier!==null));
  fs.writeFileSync(record,JSON.stringify({mode,saved,prepared:new Date().toISOString()},null,2));
 }else{
  const before=JSON.parse(fs.readFileSync(record,'utf8')),saved=before.saved.current;
  const cooperative=saved.kind==='Cooperative';
  await tp(a,cooperative?-8:0,0);await t.sleep(3200);const waiting=board().current;
  check('one reconnect cannot resume without the required participants',waiting.recovering&&waiting.phase==='Running');
  check('offline time and reconnect waiting preserve clocks',waiting.remaining_ms===saved.remaining_ms&&waiting.pause_ms===saved.pause_ms&&waiting.progress_ms===saved.progress_ms);
  check('block carrier and last location survive',waiting.carrier===saved.carrier&&JSON.stringify(waiting.block)===JSON.stringify(saved.block));
  check('installed components survive',JSON.stringify(waiting.components)===JSON.stringify(saved.components));
  check('participants and scale survive',waiting.initial_count===2&&Object.keys(waiting.members).length===2&&waiting.required_ms===saved.required_ms);
  const b=await t.connect('EventResumeB');await tp(b,cooperative?8:0,cooperative?0:12);await t.until(()=>!board().current.recovering,'automatic recovery');
  console.log('Completing the saved charge after restart...');await t.until(()=>board().current.phase==='Completed','resumed completion',70000);
  check('saved charge completes and credits one pot',board().rewards[playerId(a)]===(before.saved.rewards[playerId(a)]||0)+(cooperative?1:2));
  const credited=board().rewards[playerId(a)];await t.sleep(2000);check('recovery cannot award twice',board().rewards[playerId(a)]===credited);
  before.verified=new Date().toISOString();before.server_sha256=require('node:crypto').createHash('sha256').update(fs.readFileSync(path.join(root,'build/horizon/unknown-bin/horizon-client.exe'))).digest('hex');
  fs.writeFileSync(record,JSON.stringify(before,null,2));
  fs.copyFileSync(record,path.join(root,`build/horizon/event-restart-${before.mode.replace('prepare-','')}-results.json`));
  t.cmd('unknown stop');
 }
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{for(const b of t.bots.values())b.socket.destroy();});
