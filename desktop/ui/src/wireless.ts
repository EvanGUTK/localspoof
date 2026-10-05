export type ConnectionTransport='USB'|'WIFI';
export type WirelessService={instance:string;kind:'PAIRING'|'CONNECT';endpoint:string};
export type WirelessDevice={serial:string;model?:string;state:string;identity:string};
// Presentation validation only. Native TLS/device verification remains authoritative.
export function wirelessEndpoint(value:string):string{
 const v=value.trim();if(v.length>96||/[\s\u0000-\u001f]/.test(v))throw new Error('Enter the local IP address and port shown in Wireless debugging.');
 const m=/^(\[[0-9a-fA-F:]+\]|(?:[0-9]{1,3}\.){3}[0-9]{1,3}):([0-9]{4,5})$/.exec(v);if(!m)throw new Error('Use IP:port, or [IPv6]:port, from Wireless debugging.');
 const port=Number(m[2]);if(port<1024||port>65535||port===5555||port===5037)throw new Error('Use the authenticated wireless debugging port. Legacy TCP and ADB server ports are not supported.');
 if(!m[1]!.startsWith('[')){const octets=m[1]!.split('.').map(Number);if(octets.some(n=>n>255)||!(octets[0]===10||octets[0]===172&&octets[1]!>=16&&octets[1]!<=31||octets[0]===192&&octets[1]===168))throw new Error('Use a private local-network address from your phone.');}
 else {const address=m[1]!.slice(1,-1).toLowerCase();if(!/^f[cd][0-9a-f]{2}:/.test(address))throw new Error('Use a private local IPv6 address from your phone.');}
 return v;
}
export function pairingCode(value:string):string{const code=value.trim();if(!/^[0-9]{6}$/.test(code))throw new Error('Enter the six-digit Android wireless pairing code.');return code;}
export function wirelessServices(value:unknown):WirelessService[]{if(!Array.isArray(value)||value.length>64)return [];return value.filter((v):v is WirelessService=>!!v&&typeof v==='object'&&typeof v.instance==='string'&&v.instance.length<=256&&['PAIRING','CONNECT'].includes(v.kind)&&typeof v.endpoint==='string').flatMap(v=>{try{return [{...v,endpoint:wirelessEndpoint(v.endpoint)}];}catch{return [];}});}
