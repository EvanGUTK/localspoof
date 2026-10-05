import { open, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
export function buildCatalog({ version, versionCode, notes, android, windows, signature, signerSha256, pubDate }) {
  if (!/^\d+\.\d+\.\d+$/.test(version) || !Number.isSafeInteger(versionCode) || versionCode < 1) throw Error('Invalid release version');
  if (typeof notes !== 'string' || notes.length > 8000 || !/^\d{4}-\d{2}-\d{2}T.*Z$/.test(pubDate) || !Number.isFinite(Date.parse(pubDate))) throw Error('Invalid release metadata');
  if (!/^[a-f0-9]{64}$/.test(signerSha256) || typeof signature !== 'string' || signature.length < 100 || signature.length > 2048 || !/^[A-Za-z0-9+/=]+$/.test(signature)) throw Error('Invalid publisher signature');
  const base = `https://github.com/EvanGUTK/localspoof-releases/releases/download/v${version}/`;
  const descriptor = (platform, file, maximum, minimumOS) => {
    if (!Number.isSafeInteger(file.size) || file.size < 1 || file.size > maximum || !/^[a-f0-9]{64}$/.test(file.sha256)) throw Error('Invalid artifact bounds');
    return {platform, version, minOS:minimumOS, protocolMajor:1, protocolMin:6, protocolMax:6, url:base+file.name, size:file.size, sha256:file.sha256};
  };
  if(android.name!==`localspoof-${version}.apk` || windows.name!==`LocalSpoof_${version}_x64-setup.exe`) throw Error('Unexpected artifact filename');
  const mobile={...descriptor('android',android,128*1024*1024,29),versionCode,signerSha256};
  const desktop={...descriptor('windows-x86_64',windows,256*1024*1024,19041),signature};
  return {schemaVersion:1,version,notes,pub_date:pubDate,platforms:{'windows-x86_64':{url:desktop.url,signature}},releases:[mobile,desktop]};
}
async function inspect(filename, maximum){
  const file=await open(filename,'r');
  try{
    const metadata=await file.stat();
    if(!metadata.isFile() || metadata.size<1 || metadata.size>maximum)throw Error('Artifact bound exceeded');
    let size=0;const digest=createHash('sha256');
    for await (const chunk of file.createReadStream({autoClose:false,highWaterMark:65536})){
      size+=chunk.length;if(size>maximum)throw Error('Artifact grew beyond bound');digest.update(chunk);
    }
    if(size!==metadata.size)throw Error('Artifact changed while reading');
    return {name:path.basename(filename),size,sha256:digest.digest('hex')};
  }finally{await file.close();}
}
async function readLimitedFile(filename,maximum){
  const file=await open(filename,'r');
  try{
    const metadata=await file.stat();if(!metadata.isFile() || metadata.size>maximum)throw Error('Metadata bound exceeded');
    let size=0;const chunks=[];
    for await (const chunk of file.createReadStream({autoClose:false,highWaterMark:4096})){
      size+=chunk.length;if(size>maximum)throw Error('Metadata grew beyond bound');chunks.push(chunk);
    }
    return Buffer.concat(chunks).toString('utf8');
  }finally{await file.close();}
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href){
  const [version,code,apk,exe,sig,notes,output,signer]=process.argv.slice(2);
  const manifest=buildCatalog({version,versionCode:Number(code),android:await inspect(apk,128*1024*1024),windows:await inspect(exe,256*1024*1024),signature:(await readLimitedFile(sig,8192)).trim(),notes:await readLimitedFile(notes,32000),pubDate:new Date().toISOString(),signerSha256:signer});
  const raw=JSON.stringify(manifest,null,2)+'\n';
  if(Buffer.byteLength(raw)>65536)throw Error('Catalog bound exceeded');
  await writeFile(output,raw,'utf8');
  console.log('Bounded release catalog created. Sign exact bytes before publication.');
}
