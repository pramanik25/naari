import release from "./release.json";

export const APK_FILE_NAME = "naari-shakti.apk";

// Where /download sends people when no APK is staged on this server and APK_URL is unset:
// the asset of that name on the newest GitHub release.
export const DEFAULT_APK_URL = `https://github.com/pramanik25/NaariShakti2/releases/latest/download/${APK_FILE_NAME}`;

export const MIN_ANDROID = "7.0";

// "What's new" on the landing page, keyed by versionName. Each entry: [icon, title, body], with
// icon a key of ICONS in src/app/page.js. release.json is rewritten by `npm run apk`, so the notes
// live here; a version without an entry simply shows no "What's new" section.
const RELEASE_NOTES = {
  "2.1": [
    ["clock", "Daily commute", "Save the trips you make most days. Tap “Leaving” when you set off, and your contacts are alerted if you haven’t tapped “I’ve reached” by the usual time."],
    ["calendar", "Private cycle tracker", "Log your period, mood and symptoms and see when the next one is expected. Encrypted on your phone and never uploaded."],
    ["book", "Daily safety quiz", "Three quick questions a day on helplines, your legal rights and staying safe, with streaks, points and levels."],
    ["users", "Family circle", "See on a map where your guardians and the people you guard were last seen. Sharing your own location is off until you switch it on."],
    ["pin", "Community safety map", "Mark a spot as poorly lit, isolated, a place of harassment or an unsafe transport stop — or as a safe spot — and see what others reported nearby."],
    ["message", "Anonymous community", "Ask for advice and share what you know. Nobody sees your name, and you can report or block anyone."],
    ["globe", "Nepali", "The app is now available in Nepali, bringing it to 14 languages."],
  ],
};

export function releaseInfo() {
  return {
    ...release,
    sizeMb: Math.round(release.sizeBytes / (1024 * 1024)),
    notes: RELEASE_NOTES[release.versionName] ?? [],
  };
}
