const t=require('./test-unknown.cjs'),fs=require('node:fs'),path=require('node:path');
const configPath=path.join(__dirname,'../build/horizon/unknown-test-server/test-world/unknownmod/config.json');
const checks=[];
let heartbeat,originalConfig;
function check(name,ok){if(!ok)throw Error('FAIL '+name);checks.push(name);console.log('PASS '+name)}
function rotation(bot,yaw){const packet=Buffer.alloc(9);packet.writeFloatBE(yaw,0);packet.writeFloatBE(0,4);packet[8]=1;bot.named('SERVERBOUND_MOVE_PLAYER_ROT',packet)}
async function main(){
 originalConfig=fs.readFileSync(configPath);
 const operator=await t.connect('AfkOperator'),idle=await t.connect('AfkIdle'),active=await t.connect('AfkActive');
 t.cmd('op AfkOperator');t.cmd('deop AfkIdle');t.cmd('deop AfkActive');
 for(const bot of [operator,idle,active]){t.cmd('unknown restore '+bot.name);t.cmd('gamemode survival '+bot.name)}
 check('invalid AFK timeout rejected',t.cmd('unknown afktimeout 0').includes('от 1 до 10080'));
 check('one-minute timeout saved',t.cmd('unknown afktimeout 1').includes('1 мин')&&JSON.parse(fs.readFileSync(configPath)).afk_timeout_minutes===1);
 const deniedStart=idle.events.length;
 idle.command('unknown online');
 await t.until(()=>idle.events.length>deniedStart,'non-op rejection');
 check('non-operator cannot read private online report',idle.events.slice(deniedStart).some(e=>/оператор|command.unknown|permission/.test(e.text))&&!idle.events.some(e=>e.text.includes('Доступны для ивента')));
 rotation(idle,12);
 await t.sleep(1000);
 let yaw=20;
 const privateStart=idle.events.length;
 heartbeat=setInterval(()=>{
  idle.move();idle.named('SERVERBOUND_PLAYER_INPUT',Buffer.from([0]));rotation(idle,12);
  idle.menuRequest({action:'status'});
  rotation(active,++yaw);
 },1000);
 console.log('Waiting for real one-minute AFK deadline with neutral packets and menu polling...');
 await t.sleep(62_000);
 const report=t.cmd('unknown online');
 check('neutral movement input and menu polling do not prevent AFK',report.includes('AFK (2): AfkIdle, AfkOperator'));
 check('changing look direction keeps player active',report.includes('Онлайн без AFK (1): AfkActive'));
 check('AFK excluded from event count',report.includes('Доступны для ивента (1): AfkActive'));
 check('public menu does not expose AFK',idle.menus.length>0&&!JSON.stringify(idle.menus).includes('afk'));
 check('AFK transitions are not broadcast',!idle.events.slice(privateStart).some(e=>/AFK|AfkActive|AfkOperator/.test(e.text)));
 const before=operator.events.length;
 operator.command('unknown online');
 await t.until(()=>operator.events.slice(before).some(e=>e.text.includes('Доступны для ивента')),'private operator report');
 check('operator receives private report',operator.events.slice(before).some(e=>e.text.includes('AFK (1): AfkIdle')));
 await t.sleep(400);
 check('operator report not sent to ordinary players',!idle.events.slice(privateStart).some(e=>e.text.includes('Доступны для ивента')));
 rotation(idle,13);await t.sleep(500);
 check('activity immediately clears AFK',t.cmd('unknown afk').includes('AFK (0): —'));
 t.cmd('gamemode spectator AfkActive');await t.sleep(500);
 check('spectators stay online but do not count for events',t.cmd('unknown online').includes('Доступны для ивента (2): AfkIdle, AfkOperator'));
 idle.disconnect();await t.until(()=>idle.closed,'idle disconnect');await t.sleep(500);
 check('offline players omitted from report',!t.cmd('unknown online').includes('AfkIdle'));
 const rejoined=await t.connect('AfkIdle');
 check('reconnect starts active',t.cmd('unknown online').includes('Онлайн без AFK (3): AfkActive, AfkIdle, AfkOperator'));
 check('AFK absent from persisted player state',!JSON.stringify(t.state()).includes('afk'));
 rejoined.disconnect();
 fs.writeFileSync(path.join(__dirname,'../build/horizon/afk-test-results.json'),JSON.stringify({passed:checks.length,checks,at:new Date().toISOString()},null,2));
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{
 clearInterval(heartbeat);
 for(const bot of t.bots.values())bot.socket.destroy();
 if(originalConfig){fs.writeFileSync(configPath,originalConfig);t.cmd('unknown reload')}
});
