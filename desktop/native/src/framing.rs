use crate::adb::NativeError;
use serde_json::Value;
use std::io::{Read, Write};

pub const MAX_FRAME_BYTES: usize = 1_048_576;

pub fn read_frame(reader: &mut impl Read) -> Result<Value, NativeError> {
    let mut header = [0u8; 4];
    reader
        .read_exact(&mut header)
        .map_err(|_| NativeError::InvalidFrame)?;
    let length = u32::from_be_bytes(header) as usize;
    if length == 0 || length > MAX_FRAME_BYTES {
        return Err(NativeError::InvalidFrame);
    }
    let mut bytes = vec![0u8; length];
    reader
        .read_exact(&mut bytes)
        .map_err(|_| NativeError::InvalidFrame)?;
    let value: Value = serde_json::from_slice(&bytes).map_err(|_| NativeError::InvalidFrame)?;
    if !value.is_object() {
        return Err(NativeError::InvalidFrame);
    }
    Ok(value)
}

pub fn write_frame(writer: &mut impl Write, value: &Value) -> Result<(), NativeError> {
    let bytes = serde_json::to_vec(value).map_err(|_| NativeError::InvalidFrame)?;
    if !value.is_object() || bytes.is_empty() || bytes.len() > MAX_FRAME_BYTES {
        return Err(NativeError::InvalidFrame);
    }
    writer
        .write_all(&(bytes.len() as u32).to_be_bytes())
        .map_err(|_| NativeError::Disconnected)?;
    writer
        .write_all(&bytes)
        .map_err(|_| NativeError::Disconnected)?;
    writer.flush().map_err(|_| NativeError::Disconnected)
}
