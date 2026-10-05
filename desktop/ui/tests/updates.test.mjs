import test from 'node:test';
import assert from 'node:assert/strict';
import {updateInstallAllowed,updateError,updateMessage} from '../src/updates.ts';
import {wirelessEndpoint,pairingCode,wirelessServices} from '../src/wireless.ts';
test('Windows install is blocked during all journey states and disconnected unknown phone',()=>{
 assert.equal(updateInstallAllowed(false,false),true);
 assert.equal(updateInstallAllowed(true,false,{ok:true,state:'IDLE',payload:{desktopUpdateSafe:true}}),false);
 for(const state of ['ACTIVE','PAUSED','HOLDING','READY','ERROR'])assert.equal(updateInstallAllowed(true,true,{ok:true,state,payload:{desktopUpdateSafe:true}}),false);
 assert.equal(updateInstallAllowed(true,true,{ok:true,state:'IDLE',payload:{desktopUpdateSafe:true}}),true);
 assert.equal(updateInstallAllowed(true,true,{ok:true,state:'IDLE',payload:{}}),false);
 assert.equal(updateInstallAllowed(true,true,{ok:false,state:'IDLE',payload:{desktopUpdateSafe:true}}),false);
});
test('update errors never display raw publisher responses and download progress is meaningful',()=>{
 assert.match(updateError('UPDATE_BAD_SIGNATURE'),/verification failed/);
 assert.match(updateError('UPDATE_FEED_UNAVAILABLE'),/not published/);
 assert.match(updateError('sensitive arbitrary provider text'),/could not complete/);
 assert.ok(!updateError('sensitive arbitrary provider text').includes('sensitive'));
 assert.match(updateMessage({phase:'DOWNLOADING',currentVersion:'0.8.0',notes:'',received:1048576,total:2097152}),/1.0 of 2.0 MB/);
});
test('wireless endpoint presentation rejects public, shell and legacy ports; native verifies TLS',()=>{
 for(const value of ['8.8.8.8:44001','127.0.0.1:44001','192.168.1.2:5555','192.168.1.2:5037','10.0.0.1:65536','10.256.0.1:44444','example.com:44444','10.0.0.1:44444;id','[::1]:44444','[2001:db8::1]:44444'])assert.throws(()=>wirelessEndpoint(value),value);
 assert.equal(wirelessEndpoint(' 192.168.1.2:44001 '),'192.168.1.2:44001');
 assert.equal(wirelessEndpoint('[fd12::1]:44001'),'[fd12::1]:44001');
 assert.equal(pairingCode('012345'),'012345');assert.throws(()=>pairingCode('123'));assert.throws(()=>pairingCode('12345;id'));
 assert.deepEqual(wirelessServices([{instance:'valid',kind:'CONNECT',endpoint:'192.168.1.2:44001'},{instance:'public',kind:'CONNECT',endpoint:'8.8.8.8:44001'},{instance:'wrong',kind:'UNKNOWN',endpoint:'192.168.1.2:44001'}]),[{instance:'valid',kind:'CONNECT',endpoint:'192.168.1.2:44001'}]);
});
