import { unsafeStringify } from './stringify.js';
import v1 from './v1.js';
import v1ToV6 from './v1ToV6.js';

import type { UUIDTypes, Version6Options } from './types.js';

function v6(options?: Version6Options, buf?: undefined, offset?: number): string;
function v6<TBuf extends Uint8Array = Uint8Array>(
  options: Version6Options | undefined,
  buf: TBuf,
  offset?: number,
): TBuf;
function v6<TBuf extends Uint8Array = Uint8Array>(
  options?: Version6Options,
  buf?: TBuf,
  offset?: number,
): UUIDTypes<TBuf> {
  options ??= {};
  offset ??= 0;

  let bytes = v1({ ...options, _v6: true }, new Uint8Array(16)) as Uint8Array;
  bytes = v1ToV6(bytes);

  if (buf) {
    if (offset < 0 || offset + 16 > buf.length) {
      throw new RangeError(
        `UUID byte range ${offset}:${offset + 15} is out of buffer bounds`,
      );
    }

    for (let i = 0; i < 16; i++) {
      buf[offset + i] = bytes[i];
    }

    return buf as unknown as UUIDTypes<TBuf>;
  }

  return unsafeStringify(bytes) as UUIDTypes<TBuf>;
}

export default v6;
