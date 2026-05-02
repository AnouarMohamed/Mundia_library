import parse from './parse.js';
import { unsafeStringify } from './stringify.js';

import type { UUIDTypes } from './types.js';

export function stringToBytes(str: string): Uint8Array {
  str = unescape(encodeURIComponent(str));
  const bytes = new Uint8Array(str.length);
  for (let i = 0; i < str.length; ++i) {
    bytes[i] = str.charCodeAt(i);
  }
  return bytes;
}

export const DNS = '6ba7b810-9dad-11d1-80b4-00c04fd430c8';
export const URL = '6ba7b811-9dad-11d1-80b4-00c04fd430c8';

type HashFunction = (bytes: Uint8Array) => Uint8Array;

export default function v35<TBuf extends Uint8Array = Uint8Array>(
  version: 0x30 | 0x50,
  hash: HashFunction,
  value: string | Uint8Array,
  namespace: UUIDTypes,
  buf?: TBuf,
  offset?: number,
): UUIDTypes<TBuf> {
  const valueBytes = typeof value === 'string' ? stringToBytes(value) : value;
  const namespaceBytes =
    typeof namespace === 'string' ? parse(namespace) : namespace;

  if (namespaceBytes?.length !== 16) {
    throw new TypeError(
      'Namespace must be array-like (16 iterable integer values, 0-255)',
    );
  }

  let bytes = new Uint8Array(16 + valueBytes.length);
  bytes.set(namespaceBytes as Uint8Array);
  bytes.set(valueBytes, (namespaceBytes as Uint8Array).length);
  bytes = hash(bytes);

  bytes[6] = (bytes[6] & 0x0f) | version;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  if (buf) {
    offset ??= 0;

    if (offset < 0 || offset + 16 > buf.length) {
      throw new RangeError(
        `UUID byte range ${offset}:${offset + 15} is out of buffer bounds`,
      );
    }

    for (let i = 0; i < 16; ++i) {
      buf[offset + i] = bytes[i];
    }

    return buf as unknown as UUIDTypes<TBuf>;
  }

  return unsafeStringify(bytes) as UUIDTypes<TBuf>;
}
