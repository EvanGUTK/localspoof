const messages:Record<string,string>={
  INVALID_TOOL:'Choose an existing platform-tools adb.exe or use Find installed tools. Android Studio lists the SDK location in SDK Manager.',
  TOOL_FAILED:'ADB could not complete this operation. Reconnect USB, unlock the phone, and check USB debugging.',
  TOOL_TIMED_OUT:'ADB timed out. Reconnect the USB cable, unlock the phone, then scan again.',
  ADB_SERVER_UNAVAILABLE:'ADB could not start its local server. Close conflicting Android tools, check the selected adb.exe, then scan again.',
  ADB_UNAUTHORIZED:'Unlock the phone and approve this computer’s USB debugging prompt, then scan again.',
  ADB_OFFLINE:'The phone is offline. Reconnect USB, unlock it, and scan again.',
  USB_DEVICE_REQUIRED:'Choose an authorized phone connected by USB for this USB operation. Wi-Fi uses its separate setup.',
  INVALID_WIRELESS_ENDPOINT:'Enter the private local IP and current port shown in Android Wireless debugging. Public, legacy TCP and ADB server endpoints are not supported.',
  WIRELESS_TLS_REQUIRED:'This endpoint is not verified authenticated Android wireless debugging. Use the main Wireless debugging screen’s current connection address and port.',
  WIRELESS_PAIRING_FAILED:'Android debugging pairing did not complete. Open Pair device with pairing code again and use its fresh address, port and six-digit code.',
  WIRELESS_CONNECT_FAILED:'Android wireless debugging could not connect. Check the same Wi-Fi network, current connection port and Android debugging trust.',
  USB_VERIFICATION_UNAVAILABLE:'USB verification did not complete. Check the data cable, Windows phone driver, and USB debugging. Unplug other Android phones, then reconnect the selected phone.',
  PHONE_CONTROL_UNAVAILABLE:'Open LocalSpoof on the phone and tap Enable USB desktop control. Keep it open, then connect again.',
  PHONE_PROTOCOL_MISMATCH:'Install the verified companion APK supplied with this desktop version, open it, and enable USB desktop control again.',
  APK_INSTALL_FAILED:'APK installation failed. Unlock the phone, approve any installation prompt, and make sure enough storage is available. An existing app signed with a different key must be removed before installing this build.',
  APK_SIGNATURE_MISMATCH:'An existing LocalSpoof installation uses a different signing key, so Android cannot update it with this APK. Remove the existing app yourself if you want to replace it, then install again.',
  UNTRUSTED_APK:'This file does not match the verified signed companion for this desktop build. Clear the APK path, then use Find installed tools to select the supplied APK.',
  APK_HASH_MISMATCH:'This APK does not match the verified signed companion for this desktop build. Use the supplied APK.',
  INSTALLATION_DISABLED:'This build has no verified companion APK installation policy. Install the signed APK manually on your phone.',
  INVALID_FRAME:'The phone sent an unsupported response. Update the companion APK, reopen it, and enable USB desktop control.',
  INVALID_DEVICE:'Choose an authorized phone connected directly by USB, then scan again.',
  DISCONNECTED:'The controller connection closed. Reconnect using the selected transport and enable desktop control on the phone.',
  PAIRING_REQUIRED:'Connect and compare the approval number on both screens, then approve this desktop on the phone.',
  INVALID_SECRET:'Enter the 32-character hexadecimal secret currently shown on the phone.',
  INVALID_INPUT:'Check the selected phone and enter complete, valid paths.',
  NOT_CONNECTED:'Connect to the phone before requesting pairing.',
  CONNECTION_CLOSED:'The controller connection closed. Reconnect using the selected transport and enable desktop control on the phone.',
  UNAUTHORIZED:'App approval is no longer authorized. Reconnect and compare a fresh approval number on both screens.',
};
export function nativeErrorMessage(error:unknown):string{
  const raw=typeof error==='string'?error:typeof error==='object'&&error!==null&&'code' in error?String(error.code):'';
  const code=raw.replace(/([a-z0-9])([A-Z])/g,'$1_$2').toUpperCase();
  return messages[code]??'The operation could not complete. Check the phone, USB connection, and selected prerequisites. No device details were recorded.';
}
