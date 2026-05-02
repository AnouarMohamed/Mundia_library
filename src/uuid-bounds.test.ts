import { describe, expect, it } from "vitest";
import { v3, v5, v6 } from "uuid";

const NS = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

describe("uuid v3/v5/v6 buffer bounds checking", () => {
  describe("v5", () => {
    it("throws RangeError when buf is too small for offset", () => {
      expect(() => v5("x", NS, new Uint8Array(8), 4)).toThrow(RangeError);
    });

    it("throws RangeError when offset is negative", () => {
      expect(() => v5("x", NS, new Uint8Array(16), -1)).toThrow(RangeError);
    });

    it("throws RangeError when buf is too small (no offset)", () => {
      expect(() => v5("x", NS, new Uint8Array(8))).toThrow(RangeError);
    });

    it("succeeds when buf is exactly 16 bytes at offset 0", () => {
      const buf = new Uint8Array(16);
      expect(() => v5("x", NS, buf, 0)).not.toThrow();
    });

    it("succeeds when buf has room at the given offset", () => {
      const buf = new Uint8Array(32);
      expect(() => v5("x", NS, buf, 8)).not.toThrow();
    });
  });

  describe("v3", () => {
    it("throws RangeError when buf is too small for offset", () => {
      expect(() => v3("x", NS, new Uint8Array(8), 4)).toThrow(RangeError);
    });

    it("throws RangeError when offset is negative", () => {
      expect(() => v3("x", NS, new Uint8Array(16), -1)).toThrow(RangeError);
    });

    it("throws RangeError when buf is too small (no offset)", () => {
      expect(() => v3("x", NS, new Uint8Array(8))).toThrow(RangeError);
    });

    it("succeeds when buf is exactly 16 bytes at offset 0", () => {
      const buf = new Uint8Array(16);
      expect(() => v3("x", NS, buf, 0)).not.toThrow();
    });

    it("succeeds when buf has room at the given offset", () => {
      const buf = new Uint8Array(32);
      expect(() => v3("x", NS, buf, 8)).not.toThrow();
    });
  });

  describe("v6", () => {
    it("throws RangeError when buf is too small for offset", () => {
      expect(() => v6({}, new Uint8Array(8), 4)).toThrow(RangeError);
    });

    it("throws RangeError when offset is negative", () => {
      expect(() => v6({}, new Uint8Array(16), -1)).toThrow(RangeError);
    });

    it("throws RangeError when buf is too small (no offset)", () => {
      expect(() => v6({}, new Uint8Array(8))).toThrow(RangeError);
    });

    it("succeeds when buf is exactly 16 bytes at offset 0", () => {
      const buf = new Uint8Array(16);
      expect(() => v6({}, buf, 0)).not.toThrow();
    });

    it("succeeds when buf has room at the given offset", () => {
      const buf = new Uint8Array(32);
      expect(() => v6({}, buf, 8)).not.toThrow();
    });
  });
});
