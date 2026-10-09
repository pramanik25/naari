import "./site.css";
import Logo from "@/components/SiteLogo";
import { MIN_ANDROID, releaseInfo } from "@/lib/release";

const ICONS = {
  power: "M12 3v9m5.7-5.7a8 8 0 1 1-11.4 0",
  shake: "M8 4h8a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM3 8v8m18-8v8",
  mic: "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zm-7 9a7 7 0 0 0 14 0m-7 7v2",
  wave: "M3 12h2l2-6 4 12 3-9 2 5 1-2h4",
  fall: "M12 4a2 2 0 1 0 0 .01M6 21l3-6 3 2 2-6-4-2-4 3m8-1 4 2",
  headset: "M4 14v-2a8 8 0 0 1 16 0v2m-16 0h3v6H5a1 1 0 0 1-1-1v-5zm16 0h-3v6h2a1 1 0 0 0 1-1v-5z",
  volume: "M4 10v4h4l5 4V6l-5 4H4zm13-1a4 4 0 0 1 0 6",
  bluetooth: "M7 7l10 10-5 4V3l5 4L7 17",
  hand: "M12 21a7 7 0 0 0 7-7V8a1.5 1.5 0 0 0-3 0v3-6a1.5 1.5 0 0 0-3 0v6-7a1.5 1.5 0 0 0-3 0v8-5a1.5 1.5 0 0 0-3 0v7a7 7 0 0 0 5 6",
  message: "M4 5h16v11H9l-5 4V5z",
  phone: "M5 4h4l2 5-3 2a12 12 0 0 0 5 5l2-3 5 2v4a1 1 0 0 1-1 1A17 17 0 0 1 4 5a1 1 0 0 1 1-1z",
  pin: "M12 21s7-6.2 7-11a7 7 0 0 0-14 0c0 4.800 7 11 7 11zm0-8a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
  camera: "M4 8h3l2-3h6l2 3h3v11H4V8zm8 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
  siren: "M7 18v-5a5 5 0 0 1 10 0v5M5 18h14v3H5v-3zM12 3v2M4 6l1.500 1.500M20 6l-1.500 1.500",
  users: "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm7 0a3 3 0 1 0 0-6M2 20c0-3.300 3.100-6 7-6s7 2.700 7 6m1-6c2.800.200 5 2.500 5 6",
  radio: "M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM8 16a6 6 0 0 1 0-8m8 0a6 6 0 0 1 0 8M5 19a10 10 0 0 1 0-14m14 0a10 10 0 0 1 0 14",
  clock: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zm0-14v5l3 2",
  car: "M5 16h14v-4l-2-5H7l-2 5v4zm0 0v2m14-2v2M8 13h.010M16 13h.010",
  meet: "M8 10a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm8 0a3 3 0 1 0 0-6M3 20c0-3 2.200-5 5-5s5 2 5 5m3-5c2.800 0 5 2 5 5",
  shield: "M12 3l8 3v6c0 5-3.400 8.400-8 9.500C7.400 20.400 4 17 4 12V6l8-3z",
  share: "M12 21s7-6.200 7-11a7 7 0 0 0-14 0c0 4.800 7 11 7 11zM9 10h6m-3-3v6",
  calendar: "M5 6h14v14H5V6zm0 4h14M9 3v4m6-4v4",
  ring: "M5 4h4l2 5-3 2a12 12 0 0 0 5 5l2-3 5 2v4a1 1 0 0 1-1 1A17 17 0 0 1 4 5a1 1 0 0 1 1-1zM15 4a5 5 0 0 1 5 5",
  mute: "M4 10v4h4l5 4V6l-5 4H4zm13 0 4 4m0-4-4 4",
  folder: "M4 6h6l2 2h8v11H4V6z",
  book: "M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3V4zm0 13a3 3 0 0 1 3-3h11",
  doc: "M7 3h7l5 5v13H7V3zm7 0v5h5M10 13h6m-6 4h6",
  lock: "M7 11V8a5 5 0 0 1 10 0v3m-11 0h12v10H6V11z",
  battery: "M4 8h14v8H4V8zm14 3h2v2h-2M7 11v2",
  globe: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c3 3.500 3 14.500 0 18M12 3c-3 3.500-3 14.500 0 18",
  list: "M5 6h.010M5 12h.010M5 18h.010M9 6h10M9 12h10M9 18h10",
};

function Icon({ name, tone }) {
  return (
    <span className={`s-icon${tone ? ` ${tone}` : ""}`} aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d={ICONS[name]} />
      </svg>
    </span>
  );
}

function Tick() {
  return (
    <svg className="s-tick" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M5 12.500l4.500 4.500L19 7.500" />
    </svg>
  );
}

const GROUPS = [
  {
    title: "Ways to call for help",
    blurb: "Pick the ones that suit you. Every trigger starts the same SOS.",
    tone: "",
    items: [
      ["hand", "SOS button", "Press and hold the button on the home screen for 3 seconds. It fires straight away, even if protection is switched off."],
      ["power", "Power button", "Press the power button 3 times within 2 seconds. You can change it to 2–5 presses."],
      ["shake", "Shake", "Shake the phone hard three times in a row."],
      ["mic", "Voice phrase", "Say your trigger phrase — “help” by default, or 1–4 English words of your own. Listening happens on the phone, with no internet."],
      ["wave", "Scream detection", "Optional. An on-device sound model listens for screaming, shouting or crying and starts the SOS for you."],
      ["fall", "Fall or struggle detection", "Optional. Motion sensors recognise a fall followed by stillness, or violent shaking."],
      ["volume", "Volume keys, screen off", "Press volume-down 5 times within 3 seconds. Works with the phone locked, after you enable the accessibility service."],
      ["headset", "Headset button", "Triple-press the button on wired or Bluetooth earphones."],
      ["bluetooth", "Bluetooth panic button", "Pair a small iTag-style key-finder and double-press it from your pocket or bag."],
    ],
  },
  {
    title: "What an SOS does",
    blurb: "A 5-second countdown lets you cancel a false alarm. Then it all happens at once.",
    tone: "violet",
    items: [
      ["message", "SMS with your location", "Up to 5 emergency contacts get your message and a Google Maps link, with follow-ups every 2 minutes or every 100 m you move. Failed texts are retried when signal returns."],
      ["phone", "Automatic calls", "The app calls your first contact. If nobody picks up it moves to the next one, and it can finish by calling 112 if you choose."],
      ["pin", "Live tracking link", "Contacts open a web page — no app or login needed — showing your position on a map, your route, battery level and the evidence as it arrives."],
      ["camera", "Evidence recording", "Front and back camera photos plus audio clips, or video if you prefer. Every file gets a SHA-256 fingerprint so it can be shown to be unaltered."],
      ["siren", "Siren and strobe", "A loud siren, a flashing torch and an optional spoken “Help! Call the police!” draw attention to you."],
      ["users", "Guardian alerts", "Family or friends who also use the app link to you with a 6-character code and get a full-screen alarm on their phone."],
      ["radio", "Nearby helpers", "Volunteers within 2–5 km are alerted with your location and can tap “I’m going”. You can turn this off."],
      ["message", "Telegram and email", "Send the alert to Telegram chats and evidence photos to an email address as well."],
    ],
  },
  {
    title: "Staying safe on the way",
    blurb: "Tools for commutes, cabs and meeting someone new.",
    tone: "",
    items: [
      ["clock", "Check-in timer", "Set 15–60 minutes or a time. If you don’t tap “I’m safe” by then, an SOS starts — and your guardians are alerted by the server even if your phone has died."],
      ["car", "Cab mode", "Scan the number plate and text it to your contacts with your location. Pick your destination and the app asks “Is everything OK?” if the ride heads the wrong way or stops for too long. No answer in 60 seconds starts an SOS."],
      ["meet", "Meeting mode", "Before meeting someone, send your contacts who it is, where and until when. A check-in timer runs until the end time."],
      ["shield", "Safe places nearby", "Police stations, hospitals, clinics, pharmacies and fuel stations within 3 km, with call and walking directions. The last list is kept for offline use."],
      ["share", "Live location sharing", "Share your position by SMS once, or keep contacts updated for 15 minutes to 2 hours."],
      ["calendar", "Protection schedule", "Turn protection on and off automatically — for example every night from 10 pm to 6 am."],
    ],
  },
  {
    title: "Every day",
    blurb: "Small habits that make every day safer.",
    tone: "violet",
    items: [
      ["clock", "Daily commute", "Save the trips you make most days with their usual travel time. Tap “Leaving” when you set off. If you haven’t tapped “I’ve reached” in time, your contacts are alerted automatically."],
      ["calendar", "Cycle tracker", "Log your period, flow, mood and symptoms, and see expected days from your own averages. Everything is encrypted on your phone and never uploaded. An optional reminder two days before never mentions your period."],
      ["book", "Safety quiz", "Three questions a day on helplines, your legal rights and staying safe, each with a short explanation. Build a streak, earn points and move up five levels."],
    ],
  },
  {
    title: "Together",
    blurb: "Your people and your community, in one place.",
    tone: "",
    items: [
      ["users", "Family circle", "A map of where your guardians and the people you guard were last seen, with battery level. Sharing your own location is off until you switch it on."],
      ["pin", "Safety map", "Places other women marked as poorly lit, isolated, unsafe for transport or where harassment happened — and safe spots too. Reports are anonymous and not verified."],
      ["message", "Community", "Ask for advice and share experiences on legal, health and support topics. You get a different made-up name in every conversation, and posts reported by several people are hidden."],
    ],
  },
  {
    title: "Getting out quietly",
    blurb: "For moments when making noise would make things worse.",
    tone: "violet",
    items: [
      ["ring", "Fake call", "Make your phone ring with a caller name you choose, after 5 seconds to 5 minutes, so you have a reason to walk away."],
      ["mute", "Silent SOS", "No siren, no flashing, no screen. Your contacts are still alerted and your location and evidence are still shared."],
      ["lock", "Duress PIN", "If someone forces you to cancel, enter your duress PIN. The phone looks like the SOS stopped, but tracking and recording carry on and your contacts are told you may have been forced."],
    ],
  },
  {
    title: "Evidence and legal help",
    blurb: "So what happened is on record.",
    tone: "",
    items: [
      ["folder", "Incident history", "Every SOS is saved with its photos, audio, video and timings. Export one as a ZIP with a list of file fingerprints."],
      ["book", "Private diary", "Keep dated notes with a place and photo. Entries are encrypted on your phone, never uploaded, and can be saved as a PDF."],
      ["doc", "Complaint draft", "Turn an incident into an editable complaint for the police or the National Commission for Women, in English or Hindi. This is a starting point, not legal advice."],
      ["list", "Helpline directory", "112, 181, 1091, 100, 108, 1098 and the 1930 cyber-crime line, one tap away."],
    ],
  },
  {
    title: "Built in",
    blurb: "",
    tone: "violet",
    items: [
      ["lock", "PIN to stop an SOS", "Set a 4–6 digit PIN and nobody else can stop your SOS, end a check-in or open your diary."],
      ["battery", "Phone status alerts", "Contacts get an SMS with your last location if your phone is switched off, drops to 5% battery or has its SIM changed."],
      ["radio", "Offline nearby alert", "Optional. With no internet, phones close by that have this turned on are told someone near them needs help, over Bluetooth and Wi-Fi."],
      ["globe", "14 languages", "English, Hindi, Bengali, Marathi, Telugu, Tamil, Gujarati, Kannada, Malayalam, Punjabi, Odia, Assamese, Urdu and Nepali, in dark or light theme."],
    ],
  },
];

export default function HomePage() {
  const release = releaseInfo();
  const releasedOn = new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "Asia/Kolkata",
  }).format(new Date(release.builtAt));

  return (
    <div className="site">
      <header className="s-header">
        <div className="s-wrap">
          <a className="s-brand" href="#top">
            <Logo />
            Naari Kavach
          </a>
          <nav className="s-nav" aria-label="Sections">
            {release.notes.length > 0 && <a href="#new">What’s new</a>}
            <a href="#how">How it protects you</a>
            <a href="#features">Features</a>
            <a href="#privacy">Privacy</a>
          </nav>
          <a className="s-btn small" href="#download">
            Download
          </a>
        </div>
      </header>

      <main id="top">
        <section className="s-hero">
          <div className="s-wrap">
            <div>
              <span className="s-kicker">Free women’s safety app for Android</span>
              <h1>
                Help is on its way, <em>even when you can’t reach your phone.</em>
              </h1>
              <p className="s-lede">
                Naari Kavach alerts the people you trust with your live location, calls them, and records
                evidence — started by a button press, a shake, a word, or a scream.
              </p>
              <div className="s-cta">
                <a className="s-btn" href="/download">
                  Download for Android
                </a>
                <a className="s-btn quiet" href="#features">
                  See all features
                </a>
              </div>
              <p className="s-meta">
                Version {release.versionName} · released {releasedOn} · {release.sizeMb} MB · Android{" "}
                {MIN_ANDROID} or newer · no sign-up
              </p>
            </div>

            <div className="s-flow" aria-label="What happens when an SOS starts">
              <div className="s-flow-head">
                <span className="s-pulse" />
                <div>
                  SOS active
                  <small>What your phone does in the first seconds</small>
                </div>
              </div>
              <ol>
                <li>
                  <Tick />
                  <div>
                    <strong>Texts your contacts</strong>
                    Your message, a map link and a live tracking page
                  </div>
                </li>
                <li>
                  <Tick />
                  <div>
                    <strong>Calls them, one after another</strong>
                    Until someone answers
                  </div>
                </li>
                <li>
                  <Tick />
                  <div>
                    <strong>Records photos and audio</strong>
                    Fingerprinted and uploaded as it is captured
                  </div>
                </li>
                <li>
                  <Tick />
                  <div>
                    <strong>Alerts guardians and nearby helpers</strong>
                    With a full-screen alarm on their phones
                  </div>
                </li>
              </ol>
            </div>
          </div>
        </section>

        {release.notes.length > 0 && (
          <section className="s-section" id="new">
            <div className="s-wrap">
              <p className="s-eyebrow">What’s new</p>
              <h2>New in version {release.versionName}</h2>
              <p className="s-section-lede">Released on {releasedOn}.</p>
              <div className="s-grid" style={{ marginTop: 36 }}>
                {release.notes.map(([icon, title, body]) => (
                  <article className="s-card" key={title}>
                    <Icon name={icon} />
                    <h4>{title}</h4>
                    <p>{body}</p>
                  </article>
                ))}
              </div>
            </div>
          </section>
        )}

        <section className="s-section alt" id="how">
          <div className="s-wrap">
            <p className="s-eyebrow">How it protects you</p>
            <h2>Three things have to go right in an emergency. The app handles all three.</h2>
            <div className="s-steps">
              <div className="s-step">
                <div className="s-step-num">1</div>
                <h3>You can always raise the alarm</h3>
                <p>
                  Nine different triggers, most of which work with the phone locked or in your bag. Voice,
                  scream and fall detection run on the phone, so they need no internet.
                </p>
              </div>
              <div className="s-step">
                <div className="s-step-num">2</div>
                <h3>The right people know where you are</h3>
                <p>
                  SMS and calls go out over the mobile network, so they work without data. With data, contacts
                  also follow you live on a map, and guardians and nearby volunteers are alerted.
                </p>
              </div>
              <div className="s-step">
                <div className="s-step-num">3</div>
                <h3>There is a record, and it can’t be quietly stopped</h3>
                <p>
                  Photos and audio are captured and fingerprinted from the first second. A PIN stops anyone else
                  cancelling the SOS, and a duress PIN only pretends to.
                </p>
              </div>
            </div>
          </div>
        </section>

        <section className="s-section" id="features">
          <div className="s-wrap">
            <p className="s-eyebrow">Features</p>
            <h2>Everything in the app</h2>
            <p className="s-section-lede">
              Turn on protection from the home screen and the triggers below stay ready in the background.
            </p>

            {GROUPS.map((group) => (
              <div className="s-group" key={group.title}>
                <div className="s-group-head">
                  <h3>{group.title}</h3>
                  {group.blurb && <p>{group.blurb}</p>}
                </div>
                <div className="s-grid">
                  {group.items.map(([icon, title, body]) => (
                    <article className="s-card" key={title}>
                      <Icon name={icon} tone={group.tone} />
                      <h4>{title}</h4>
                      <p>{body}</p>
                    </article>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </section>

        <section className="s-section alt" id="privacy">
          <div className="s-wrap">
            <p className="s-eyebrow">Privacy</p>
            <h2>What stays on your phone, and what is shared during an SOS</h2>
            <ul className="s-points">
              <li>
                <Tick />
                <span>
                  <strong>No account.</strong> There is no sign-up, email or password. Your phone registers
                  anonymously.
                </span>
              </li>
              <li>
                <Tick />
                <span>
                  <strong>Listening stays on the phone.</strong> Voice and scream detection run on-device, and
                  nothing is recorded until an SOS starts.
                </span>
              </li>
              <li>
                <Tick />
                <span>
                  <strong>During an SOS, data goes to our server.</strong> Your name, message, location trail
                  and evidence are uploaded so your contacts can see them on the tracking page.
                </span>
              </li>
              <li>
                <Tick />
                <span>
                  <strong>Tracking links expire.</strong> A link can’t be guessed, and it stops showing location
                  and evidence 24 hours after the SOS ends.
                </span>
              </li>
              <li>
                <Tick />
                <span>
                  <strong>Your diary never leaves the phone.</strong> It is encrypted with a key held in the
                  phone’s secure hardware.
                </span>
              </li>
              <li>
                <Tick />
                <span>
                  <strong>You can erase it.</strong> “Delete my cloud data” in settings removes your incidents,
                  evidence and guardian links from the server.
                </span>
              </li>
            </ul>
            <p className="s-section-lede">
              <a href="/privacy">Read the full privacy policy</a>
            </p>
          </div>
        </section>

        <section className="s-section" id="download">
          <div className="s-wrap">
            <p className="s-eyebrow">Download</p>
            <h2>Install Naari Kavach on your phone</h2>
            <p className="s-section-lede">
              The app is downloaded directly from this site as an APK file. Open this page on your Android phone
              and follow the steps.
            </p>

            <div className="s-download">
              <div className="s-panel">
                <h3>Naari Kavach for Android</h3>
                <p style={{ color: "var(--s-muted)" }}>Free. No ads. No sign-up.</p>
                <a className="s-btn" href="/download">
                  Download APK ({release.sizeMb} MB)
                </a>
                <dl className="s-facts">
                  <dt>Version</dt>
                  <dd>{release.versionName}</dd>
                  <dt>Released</dt>
                  <dd>{releasedOn}</dd>
                  <dt>Requires</dt>
                  <dd>Android {MIN_ANDROID} or newer</dd>
                  <dt>SHA-256</dt>
                  <dd className="s-hash">{release.sha256}</dd>
                </dl>
              </div>

              <div className="s-panel">
                <h3>How to install</h3>
                <ol className="s-install">
                  <li>
                    <span>
                      <strong>Tap Download APK.</strong> The file is large, so Wi-Fi is best. If your browser
                      warns about the file type, choose “Download anyway”.
                    </span>
                  </li>
                  <li>
                    <span>
                      <strong>Pause Play Protect for the installation.</strong> Open the Play Store, tap your
                      profile picture › Play Protect › the settings icon, and pause it or switch off “Scan apps
                      with Play Protect”. It can block apps that don’t come from the Play Store. Switch it back
                      on as soon as the app is installed.
                    </span>
                  </li>
                  <li>
                    <span>
                      <strong>Open the downloaded file</strong> from the notification or your Downloads folder.
                    </span>
                  </li>
                  <li>
                    <span>
                      <strong>Allow your browser to install apps</strong> when Android asks — tap Settings, switch
                      on “Allow from this source”, then go back.
                    </span>
                  </li>
                  <li>
                    <span>
                      <strong>Tap Install</strong>, then open the app.
                    </span>
                  </li>
                  <li>
                    <span>
                      <strong>Add your emergency contacts and grant the permissions</strong> it asks for. SMS,
                      phone, location, microphone and camera are what make the SOS work.
                    </span>
                  </li>
                </ol>
                <p className="s-note">
                  Then turn on protection and try a test SOS with a contact who knows it’s a test. Set battery
                  usage to “Unrestricted” so Android doesn’t stop the app in the background.
                </p>
              </div>
            </div>
          </div>
        </section>
      </main>

      <footer className="s-footer">
        <div className="s-wrap">
          <p>
            <strong>In immediate danger, call 112.</strong> Naari Kavach is an aid, not a replacement for
            emergency services. SMS and calls need mobile signal; live tracking and guardian alerts need
            internet.
          </p>
          <p>
            © {new Date().getFullYear()} Naari Kavach · <a href="/privacy">Privacy policy</a>
          </p>
        </div>
      </footer>
    </div>
  );
}
