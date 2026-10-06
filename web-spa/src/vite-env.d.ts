/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ENABLE_LEARNING_RESOURCES?: string;
  readonly VITE_ENABLE_MEMBER_PROFILE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
