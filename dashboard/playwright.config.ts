import path from "node:path";
import { defineConfig } from "@playwright/test";

const APP = 3100;
const API = 4010;

export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: `http://127.0.0.1:${APP}`,
    channel: "chrome",
    trace: "off",
  },
  webServer: [
    {
      command: "node e2e/mock-api.mjs",
      url: `http://127.0.0.1:${API}/__health`,
      reuseExistingServer: false,
      env: { MOCK_PORT: String(API) },
    },
    {
      command: `npx next build && npx next start -p ${APP}`,
      url: `http://127.0.0.1:${APP}/login`,
      timeout: 180_000,
      reuseExistingServer: false,
      env: {
        ROOK_API_URL: `http://127.0.0.1:${API}`,
        ROOK_EVAL_DIR: path.join(__dirname, "..", "eval", "results"),
      },
    },
  ],
});
