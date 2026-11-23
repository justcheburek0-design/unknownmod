// Real automatic placement/start on port 25569. All terrain edits stay in the QA world.
const t=require('./test-unknown.cjs'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..'),data=path.join(root,'build/horizon/unknown-test-server/test-world/unknownmod');
const board=()=>JSON.parse(fs.readFileSync(path.join(data,'events.json'),'utf8'));
const pid=b=>BigInt('0x'+b.uuid).toString();let checks=0,original;
function check(name,ok){if(!ok)throw Error('FAIL '+name);checks++;console.log('PASS '+name);}
async function command(bot,text,needle){const n=bot.events.length;bot.command(text);await t.until(()=>bot.events.slice(n).some(e=>e.text.includes(needle)),text);}
async function tp(bot,p){t.cmd(`tp ${bot.name} ${p.join(' ')}`);await t.until(()=>p.every((v,i)=>Math.abs(bot.position[i]-v)<=.6),'teleport');await t.sleep(300);}
function total(b){return Object.values(b.rewards).reduce((sum,n)=>sum+n,0);}
async function request(){
 for(let i=0;i<4;i++){
  t.cmd('unknown event auto now');
  await t.until(()=>!board().schedule.forced&&(board().current?.phase==='Gathering'||board().schedule.reason.includes('Нет безопасной')),'placement attempt');
  if(board().current?.phase==='Gathering')return;
 }
 throw Error('No safe QA site: '+board().schedule.reason);
}
async function main(){
 await new Promise((resolve,reject)=>{const end=Date.now()+60000;function probe(){const socket=require('node:net').createConnection({host:'127.0.0.1',port:25569});socket.once('connect',()=>{socket.destroy();resolve();});socket.once('error',()=>{socket.destroy();if(Date.now()>end)reject(Error('QA server not ready'));else setTimeout(probe,250);});}probe();});
 original=fs.readFileSync(path.join(data,'config.json'));
 const a=await t.connect('AutoAlice'),b=await t.connect('AutoBob'),c=await t.connect('AutoCarol');
 for(const bot of [a,b,c]){t.cmd('deop '+bot.name);t.cmd('unknown restore '+bot.name);t.cmd('gamemode survival '+bot.name);}
 t.cmd('unknown event cancel');t.cmd('unknown start');t.cmd('unknown event auto on');
 t.cmd('team add AutoQA');for(const bot of [a,b,c])t.cmd('team join AutoQA '+bot.name);
 t.cmd('worldborder set 59999968');t.cmd('worldborder center 0 0');
 for(const [i,bot] of [a,b,c].entries())await tp(bot,[i*2,-60,0]);
 t.cmd('fill -64 -61 -64 64 -61 64 minecraft:grass_block');
 t.cmd('unknown event exclude AutoCarol');t.cmd('unknown event auto now');
 await t.until(()=>board().schedule.reason.includes('Недостаточно'),'minimum online');
 check('automatic event waits for enough eligible online',!board().current||!['Prepared','Gathering','Running'].includes(board().current.phase));
 t.cmd('unknown event exclude');t.cmd('worldborder set 60');t.cmd('unknown event auto now');
 await t.until(()=>board().schedule.reason.includes('Нет безопасной'),'small border rejection');
 check('tiny border postpones event instead of spawning outside',!board().current||!['Gathering','Running'].includes(board().current.phase));
 check('failed placement gets a bounded retry',board().schedule.remaining_ms>=299000&&board().schedule.remaining_ms<=600000);
 t.cmd('worldborder set 256');await request();let e=board().current;
 check('expanding current border permits automatic placement',e.automatic&&e.phase==='Gathering');
 check('entire arena fits current border with safety margin',Math.abs(e.center[0])+e.arena_radius+16<128&&Math.abs(e.center[2])+e.arena_radius+16<128);
 check('natural terrain has real per-point positions',e.point_positions.length===4&&e.point_positions.every(p=>p[1]===-60));
 check('one online team gets cooperative task',e.kind==='Cooperative');
 const messages=a.events.length;a.command('event status');await t.sleep(600);
 check('public gathering does not leak private timing or type',!a.events.slice(messages).some(x=>/remaining_ms|pace_percent|reward_seed|Cooperative|Battle/.test(x.text)));
 await command(c,'event leave','Участие прекращено');
 for(const bot of [a,b,c])await tp(bot,e.center);
 await t.until(()=>board().current.members[pid(a)]&&board().current.members[pid(b)],'automatic enrollment');
 check('approaching enrolls participants without commands',board().current.members[pid(a)].joined&&board().current.members[pid(b)].joined);
 check('opt-out before arrival stays excluded',!board().current.members[pid(c)]);
 console.log('Waiting for real randomized gathering and stable quorum...');
 await t.until(()=>board().current.phase==='Running','automatic start with no operator start',150000);e=board().current;
 check('starts automatically and scales to actual attendees',e.initial_count===2&&e.points===2);
 check('hold duration is randomized within safe bounds',e.required_ms>=8000&&e.required_ms<=12000);
 const before=total(board());
 await tp(a,e.point_positions[0]);await tp(b,e.point_positions[1]);
 if(e.scenario==='Beacon'){await command(a,'event interact','Компонент установлен');await command(b,'event interact','Компонент установлен');}
 await t.until(()=>board().current.phase==='Completed','automatic encounter completion',22000);
 check('random reward pool remains between 100 and 150 percent',total(board())-before>=2&&total(board())-before<=3);
 await t.until(()=>board().schedule.remaining_ms!==null,'next randomized cooldown');
 check('completion schedules a new bounded random interval',board().schedule.remaining_ms>=90*60000-2000&&board().schedule.remaining_ms<=180*60000);
 t.cmd('unknown event cancel');t.cmd('worldborder set 59999968');t.cmd('worldborder center 512 512');
 for(const [i,bot] of [a,b,c].entries())await tp(bot,[512+i*2,-60,512]);
 await t.sleep(1200);t.cmd('fill 448 -61 448 576 -61 576 minecraft:grass_block');t.cmd('worldborder set 256');await request();e=board().current;
 check('next event reads new border center rather than old coordinates',e.center[0]>384&&e.center[0]<640&&e.center[2]>384&&e.center[2]<640);
 for(const bot of [a,b,c])await tp(bot,e.center);
 t.cmd(`worldborder center ${e.center[0]} ${e.center[2]}`);t.cmd('worldborder set 60');
 await t.until(()=>board().current.phase==='Cancelled','border shrink cancellation');
 check('shrinking border cancels unsafe gathering',board().current.phase==='Cancelled');
 const result={passed:checks,server_sha256:require('node:crypto').createHash('sha256').update(fs.readFileSync(path.join(root,'build/horizon/unknown-bin/horizon-client.exe'))).digest('hex'),finished:new Date().toISOString()};
 fs.writeFileSync(path.join(root,'build/horizon/automatic-events-test-results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{
 try{t.cmd('unknown event cancel');t.cmd('worldborder set 59999968');t.cmd('worldborder center 0 0');t.cmd('unknown stop');if(original){fs.writeFileSync(path.join(data,'config.json'),original);t.cmd('unknown reload');}}catch{}
 for(const bot of t.bots.values())bot.socket.destroy();
});
