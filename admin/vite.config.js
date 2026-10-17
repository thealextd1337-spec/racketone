import { defineConfig, loadEnv } from "vite";
import { stagingHeaders } from "./deploy/staging.js";

export default defineConfig(({ command, mode }) => ({
  base: "./",
  plugins: command === "build" && mode === "staging" ? [{
    name: "staging-security-headers",
    buildStart() {
      this.emitFile({
        type: "asset",
        fileName: "_headers",
        source: stagingHeaders(loadEnv(mode, process.cwd(), "VITE_")),
      });
    },
  }] : [],
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  server: {
    host: "127.0.0.1",
  },
}));
