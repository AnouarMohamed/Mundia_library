/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ENABLE_LEARNING_RESOURCES?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
