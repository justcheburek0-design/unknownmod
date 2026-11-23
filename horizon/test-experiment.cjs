// Exercise the server commands through real 26.3 protocol connections.
const t=require('./test-unknown.cjs');
const checks=[];
function check(name,ok){if(!ok)throw Error('FAIL '+name);checks.push(name);console.log('PASS '+name)}
async function main(){
 const a=await t.connect('SeasonA'),b=await t.connect('SeasonB');
 if(process.argv.includes('--expect-stopped')){
  await t.until(()=>b.profileOf(a)?.name==='SeasonA','normal profile after restart');
  check('server restart preserves stopped state and normal appearance',t.state().stopped&&!b.profileOf(a).properties.unknownmod_anonymous);
  return;
 }
 for(const p of [a,b]){t.cmd('unknown restore '+p.name);t.cmd('gamemode survival '+p.name)}
 t.cmd('unknown stop');t.cmd('unknown hide');
 await t.until(()=>b.profileOf(a)?.name==='SeasonA','normal profile');
 const alias=b.seenUuid(a);
 check('stop restores real names and removes anonymous skin marker',!b.profileOf(a).properties.unknownmod_anonymous);
 check('stop keeps public entity UUID',alias&&alias!==a.uuid);
 const messageCount=b.events.length;b.command('unknown start');
 await t.until(()=>b.events.length>messageCount,'permission rejection');
 check('non-operator cannot start experiment',t.state().stopped);
 check('status shows stopped experiment',t.cmd('unknown status').includes('остановлен'));
 check('reveal is rejected while stopped',t.cmd('unknown reveal SeasonA').includes('/unknown start'));
 t.cmd('unknown start');
 await t.until(()=>b.profileOf(a)?.properties.unknownmod_anonymous==='1','anonymous skin');
 check('start updates connected clients to common name and skin',b.profileOf(a).name==='JustPlayer'&&!b.profileOf(a).properties.textures);
 check('start keeps UUID and entity profile aligned',b.seenUuid(a)===alias&&b.profiles.has(alias));
 check('repeated start is harmless',t.cmd('unknown start').includes('уже'));
 t.cmd('unknown reveal SeasonA 1');
 await t.until(()=>b.profileOf(a)?.name==='SeasonA','reveal before pause');
 t.cmd('unknown stop');const before=t.state(),remaining=Object.values(before.reveals)[0].Paused.remaining;
 a.disconnect();await t.sleep(1200);const rejoined=await t.connect('SeasonA');
 await t.until(()=>b.profileOf(rejoined)?.name==='SeasonA','reconnect while paused');
 check('reconnect stays unmasked while stopped',!b.profileOf(rejoined).properties.unknownmod_anonymous);
 const after=t.state();check('reconnect does not advance paused hunt',Object.values(after.reveals)[0].Paused.remaining===remaining);
 t.cmd('unknown start');await t.sleep(500);
 const resumed=Object.values(t.state().reveals)[0].Paused.remaining;
 check('start resumes hunt from saved remaining time',resumed<=remaining&&remaining-resumed<3000);
 t.cmd('unknown hide');await t.until(()=>b.profileOf(rejoined)?.properties.unknownmod_anonymous==='1','hide after resume');
 t.cmd('unknown stop');await t.until(()=>b.profileOf(rejoined)?.name==='SeasonA','final normal skin');
 console.log(JSON.stringify({passed:checks.length,checks}));
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{for(const b of t.bots.values())b.socket.destroy()});
