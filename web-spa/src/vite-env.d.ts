/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ENABLE_LEARNING_RESOURCES?: string;
  readonly VITE_ENABLE_MEMBER_PROFILE?: string;
  readonly VITE_ENABLE_CIRCULATION_SELF_SERVICE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
