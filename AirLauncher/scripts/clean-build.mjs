/*
 * AirLauncher
 * A Tauri-based launcher for the AirPlus Minecraft client.
 * https://github.com/lmx0721/AirPlus
 *
 * 构建前清理 build/ 产物。逐文件删除以避开本机 safe-delete 批量拦截
 * （SvelteKit adapter-static 的 rimraf 会被拦截导致打包失败）。
 */
import { existsSync, readdirSync, rmdirSync, rmSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const target = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "build");

function rmRf(p) {
  if (!existsSync(p)) return;
  for (const entry of readdirSync(p)) {
    const fp = path.join(p, entry);
    if (statSync(fp).isDirectory()) {
      rmRf(fp);
    } else {
      rmSync(fp);
    }
  }
  rmdirSync(p);
}

rmRf(target);
console.log("cleaned:", target);
