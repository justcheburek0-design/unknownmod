const t=require('./test-unknown.cjs');
let checks=0;
function check(name,ok){if(!ok)throw Error('FAIL '+name);checks++;console.log('PASS '+name)}
async function main(){
 const operator=await t.connect('ScheduleOp'),player=await t.connect('SchedulePlayer');
 t.cmd('op ScheduleOp');t.cmd('deop SchedulePlayer');await t.sleep(500);
 const start=player.events.length;
 for(const command of ['unknown status','unknown reveal status']){
  const before=player.events.length;player.command(command);
  await t.until(()=>player.events.length>before,'non-op status rejection');
  check('non-op denied '+command,!player.events.slice(before).some(e=>e.text.includes('до следующей проверки')));
 }
 const menus=player.menus.length;player.menuRequest({action:'status'});
 await t.until(()=>player.menus.length>menus,'public menu');
 const menu=player.menus.at(-1);
 check('ordinary menu has no schedule or admin settings',!('admin'in menu)&&!JSON.stringify(menu).includes('next_reveal')&&!('interval'in menu));
 const before=operator.events.length;operator.command('unknown reveal status');
 await t.until(()=>operator.events.slice(before).some(e=>e.text.includes('до следующей проверки')),'operator schedule response');
 check('operator can privately query schedule',operator.events.slice(before).some(e=>e.text.includes('до следующей проверки')));
 check('console can query schedule',t.cmd('unknown status').includes('до следующей проверки'));
 await t.sleep(500);
 check('schedule replies are not broadcast',!player.events.slice(start).some(e=>e.text.includes('до следующей проверки')));
 console.log(JSON.stringify({passed:checks}));
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{for(const bot of t.bots.values())bot.socket.destroy()});
