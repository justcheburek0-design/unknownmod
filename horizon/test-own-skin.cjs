// Isolated port 25569 only. Does not touch the user's client or main world.
const t=require('./test-unknown.cjs');
function check(name,ok){if(!ok)throw Error('FAIL '+name);console.log('PASS '+name)}
async function main(){
 const a=await t.connect('SkinOwnerA'),b=await t.connect('SkinOwnerB');
 t.cmd('unknown reveal cancel');
 await t.until(()=>Boolean(a.profileOf(b))&&Boolean(b.profileOf(a)),'both profile lists');
 await t.sleep(1200);
 for(const [owner,other] of [[a,b],[b,a]]){
  check(owner.name+' is not masked to self',!owner.profiles.get(owner.uuid)?.properties.unknownmod_anonymous);
  check(owner.name+' remains masked to others',other.profileOf(owner)?.properties.unknownmod_anonymous==='1');
 }
 t.cmd('unknown reveal SkinOwnerB 1');
 await t.until(()=>a.profileOf(b)?.name==='SkinOwnerB','reveal');
 check('reveal clears other-player mask',!a.profileOf(b).properties.unknownmod_anonymous);
 t.cmd('unknown hide SkinOwnerB');
 await t.until(()=>a.profileOf(b)?.properties.unknownmod_anonymous==='1','hide');
 check('hiding preserves own skin',!b.profiles.get(b.uuid).properties.unknownmod_anonymous);
 b.disconnect();await t.until(()=>b.closed,'disconnect');
 const rejoined=await t.connect('SkinOwnerB');
 check('reconnect keeps own skin visible',!rejoined.profiles.get(rejoined.uuid)?.properties.unknownmod_anonymous);
 check('reconnect keeps other player hidden',rejoined.profileOf(a)?.properties.unknownmod_anonymous==='1');
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{for(const b of t.bots.values())b.socket.destroy()});
