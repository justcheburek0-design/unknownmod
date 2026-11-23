// Full-length encounters over the real protocol, isolated world on port 25569.
const t=require('./test-unknown.cjs'),fs=require('node:fs'),path=require('node:path');
const file=path.resolve(__dirname,'../build/horizon/unknown-test-server/test-world/unknownmod/events.json');
const configFile=path.resolve(__dirname,'../build/horizon/unknown-test-server/test-world/unknownmod/config.json');
const state=()=>JSON.parse(fs.readFileSync(file,'utf8'));
const id=b=>BigInt('0x'+b.uuid).toString();
let passed=0,originalConfig;
function check(name,ok){if(!ok)throw Error('FAIL '+name);passed++;console.log('PASS '+name)}
async function command(bot,text,needle){const n=bot.events.length;bot.command(text);await t.until(()=>bot.events.slice(n).some(e=>e.text.includes(needle)),text+' -> '+needle);}
async function tp(bot,x,z){t.cmd(`tp ${bot.name} ${x} -60 ${z}`);await t.until(()=>Math.abs(bot.position[0]-x)<=.6&&Math.abs(bot.position[2]-z)<=.6,'teleport '+bot.name);await t.sleep(350);}
async function prepare(a,b,scenario,kind){
 t.cmd('unknown event cancel');await tp(a,0,12);await tp(b,1,12);
 const result=t.cmd(`unknown event prepare ${scenario} ${kind} 0 -60 0`);check('prepare '+scenario+' '+kind,result.includes('подготовлено'));
 t.cmd('unknown event open');await command(a,'event join','Ты участвуешь');await command(b,'event join','Ты участвуешь');
 const start=t.cmd('unknown event start');check('start '+scenario+' '+kind,start.includes('Осталось'));
 check('two attendees set small tier',state().current.initial_count===2&&state().current.points===2);
}
async function waitComplete(){await t.until(()=>state().current.phase==='Completed','full-length completion',75000);}
async function main(){
 originalConfig=fs.readFileSync(configFile);
 const a=await t.connect('EventAlice'),b=await t.connect('EventBob'),watch=await t.connect('EventWatcher');
 let particles=0,actionBars=0;const packet=a.packet.bind(a);a.packet=(type,body)=>{if(type==='CLIENTBOUND_LEVEL_PARTICLES')particles++;if(type.includes('ACTION_BAR'))actionBars++;packet(type,body);};
 for(const bot of [a,b,watch]){t.cmd('deop '+bot.name);t.cmd('unknown restore '+bot.name);t.cmd('gamemode survival '+bot.name);}
 t.cmd('difficulty peaceful');t.cmd('unknown start');t.cmd('unknown event cancel');
 t.cmd('unknown event exclude EventWatcher');
 await tp(a,0,12);await tp(b,1,12);await tp(watch,2,12);
 const before=watch.events.length;t.cmd('unknown event prepare ruins coop 0 -60 0');await t.sleep(1100);
 check('preparation not broadcast',!watch.events.slice(before).some(e=>e.text.includes('подготовлено')));
 await command(watch,'event status','Нет открытого события');
 watch.command('unknown event cancel');await t.sleep(600);check('non-op cannot cancel private preparation',state().current.phase==='Prepared');
 await command(watch,'event join','Нет открытого события');
 t.cmd('unknown event open');await command(watch,'event join','живым активным');
 await command(a,'event join','Ты участвуешь');check('single player cannot start',t.cmd('unknown event start').includes('минимум два'));
 await command(b,'event join','Ты участвуешь');t.cmd('op '+watch.name);await t.sleep(500);
 await command(watch,'unknown event start','Удерживайте');t.cmd('deop '+watch.name);
 check('operator can start from an in-game command',state().current.phase==='Running');
 check('excluded observer not counted',state().current.initial_count===2);
 await tp(a,-8,0);await tp(b,8,0);await waitComplete();
 check('objective markers reach the real client',particles>0);
 check('event progress reaches the real action bar',actionBars>0);
 check('ruins cooperative credits each holder',state().rewards[id(a)]>=1&&state().rewards[id(b)]>=1);
 const rewardBefore=state().rewards[id(a)];
 t.cmd('clear '+a.name);await command(a,'event claim','Получена доля');
 check('claim persisted and reservation cleared',state().rewards[id(a)]===rewardBefore-1&&!state().reservations[id(a)]);
 check('real bread delivered',/4/.test(t.cmd('clear '+a.name+' minecraft:bread')));
 check('real arrows delivered',/8/.test(t.cmd('clear '+a.name+' minecraft:arrow')));
 check('real iron delivered',/2/.test(t.cmd('clear '+a.name+' minecraft:iron_ingot')));
 if(!state().rewards[id(a)])await command(a,'event claim','Нет заработанных');
 await prepare(a,b,'beacon','coop');await command(a,'event interact','Подойди к точке');
 await tp(a,-8,0);await tp(b,8,0);await command(a,'event interact','Компонент установлен');await command(b,'event interact','Компонент установлен');
 await waitComplete();check('beacon cooperative finished',state().current.phase==='Completed');
 await prepare(a,b,'ruins','battle');await tp(a,0,0);await tp(b,0,1);await t.sleep(1100);const contested=JSON.stringify(state().current.side_progress);await t.sleep(2200);
 check('contested center makes no progress',JSON.stringify(state().current.side_progress)===contested);
 await tp(b,0,12);console.log('Running the real 60-second ruins capture...');await waitComplete();
 check('ruins battle winner credited',state().current.winner==='player:'+a.uuid);
 await prepare(a,b,'beacon','battle');await tp(a,-8,0);await command(a,'event interact','Блок у тебя');await tp(a,0,0);
 await t.sleep(2500);const charge=state().current.progress_ms;check('carrier charges beacon',charge>0);
 await command(a,'event drop','Блок оставлен');await tp(a,0,12);await tp(b,0,0);await command(b,'event interact','Блок у тебя');
 console.log('Running the real beacon charge after takeover...');await waitComplete();
 check('beacon takeover preserves charge and rewards new owner',state().current.winner==='player:'+b.uuid);
 await prepare(a,b,'beacon','battle');await tp(a,-8,0);await command(a,'event interact','Блок у тебя');await tp(a,0,0);
 await tp(b,100,0);await t.until(()=>state().current.paused,'online shortage pause');const frozen=state().current.progress_ms;await t.sleep(2100);
 check('shortage freezes charge and cannot grant solo victory',state().current.progress_ms===frozen&&state().current.phase==='Running');
 await tp(b,0,12);await t.until(()=>!state().current.paused,'resume');
 await command(a,'event leave','Участие прекращено');await t.until(()=>state().current.carrier===null,'dropped carrier');
 check('leaving drops server-owned block',state().current.carrier===null);
 t.cmd('unknown stop');check('season stop cancels encounter',state().current.phase==='Cancelled');
 const engine=fs.readFileSync(path.resolve(__dirname,'../build/horizon/unknown-bin/horizon-client.exe'));
 const result={passed,server_sha256:require('node:crypto').createHash('sha256').update(engine).digest('hex'),finished:new Date().toISOString()};fs.writeFileSync(path.resolve(__dirname,'../build/horizon/events-test-results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{
 try{t.cmd('unknown event cancel');if(originalConfig){fs.writeFileSync(configFile,originalConfig);t.cmd('unknown reload');}}catch{}
 for(const bot of t.bots.values())bot.socket.destroy();
});
