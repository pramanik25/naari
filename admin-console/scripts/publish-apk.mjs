// Stages the latest release APK for the website's /download link.
//
//   npm run apk            (after Build > Generate Signed APK in Android Studio)
//
// Copies app/build/outputs/apk/release/app-release.apk to downloads/naari-shakti.apk
// (git-ignored — too large for git) and records its version, size and SHA-256 in
// src/lib/release.json, which the site displays next to the download button.
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const consoleDir = path.resolve(here, "..");
const releaseDir = path.resolve(consoleDir, "..", "app", "build", "outputs", "apk", "release");
const source = process.argv[2] ? path.resolve(process.argv[2]) : path.join(releaseDir, "app-release.apk");
const target = path.join(consoleDir, "downloads", "naari-shakti.apk");

if (!fs.existsSync(source)) {
  console.error(`No APK at ${source}\nBuild a signed release APK first, or pass its path as an argument.`);
  process.exit(1);
}

let version = {};
const metaFile = path.join(path.dirname(source), "output-metadata.json");
if (fs.existsSync(metaFile)) {
  const el = JSON.parse(fs.readFileSync(metaFile, "utf8")).elements?.[0] ?? {};
  version = { versionName: el.versionName, versionCode: el.versionCode };
}

fs.mkdirSync(path.dirname(target), { recursive: true });
fs.copyFileSync(source, target);

const hash = crypto.createHash("sha256");
for await (const chunk of fs.createReadStream(target)) hash.update(chunk);
const stat = fs.statSync(target);

const release = {
  versionName: version.versionName ?? "unknown",
  versionCode: version.versionCode ?? null,
  sizeBytes: stat.size,
  sha256: hash.digest("hex"),
  builtAt: fs.statSync(source).mtime.toISOString(),
};
fs.writeFileSync(path.join(consoleDir, "src", "lib", "release.json"), JSON.stringify(release, null, 2) + "\n");

console.log(`Staged ${path.relative(consoleDir, target)}`);
console.log(release);
console.log(
  "\nNext: upload downloads/naari-shakti.apk as an asset named naari-shakti.apk on a new GitHub release,\n" +
    "then commit src/lib/release.json. See README.md > Publishing a new APK."
);
