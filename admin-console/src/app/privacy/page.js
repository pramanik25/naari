import "../site.css";
import Logo from "@/components/SiteLogo";

// Shown in "Contact us". Leave empty to hide the email line until an address is decided.
const CONTACT_EMAIL = "";
const LAST_UPDATED = "9 October 2026";

export const metadata = {
  title: "Privacy policy — Naari Kavach",
  description:
    "What the Naari Kavach women's safety app collects, why it needs each permission, who receives your data during an SOS, and how to delete it.",
};

export default function PrivacyPage() {
  return (
    <div className="site">
      <header className="s-header">
        <div className="s-wrap">
          <a className="s-brand" href="/">
            <Logo />
            Naari Kavach
          </a>
          <nav className="s-nav" aria-label="Site">
            <a href="/#features">Features</a>
            <a href="/#privacy">Privacy</a>
          </nav>
          <a className="s-btn small" href="/#download">
            Download
          </a>
        </div>
      </header>

      <main className="s-section">
        <div className="s-wrap s-legal">
          <p className="s-eyebrow">Privacy policy</p>
          <h1>Naari Kavach privacy policy</h1>
          <p>Last updated: {LAST_UPDATED}</p>
          <p>
            Naari Kavach is a women’s safety app for Android. This policy explains what the app and this
            website collect, why, who receives it, and how you can delete it. It applies to the Naari Kavach
            app, the live tracking page your contacts open during an SOS, and this website.
          </p>

          <h2>In short</h2>
          <ul>
            <li>
              <strong>No account.</strong> There is no sign-up, email or password. Your phone registers
              anonymously.
            </li>
            <li>
              <strong>No ads and no selling of data.</strong> We do not show ads, and we do not sell or rent
              your data to anyone.
            </li>
            <li>
              <strong>Listening stays on the phone.</strong> Voice and scream detection run on your device.
              Nothing is recorded until an SOS starts.
            </li>
            <li>
              <strong>During an SOS, data goes to our server</strong> so the people you chose can see where
              you are and what is happening.
            </li>
            <li>
              <strong>You can delete it.</strong> “Delete my cloud data” in the app’s settings removes your
              data from our server.
            </li>
          </ul>

          <h2>What stays on your phone</h2>
          <p>These are stored on your phone and are not uploaded to our server:</p>
          <ul>
            <li>Your private diary and cycle tracker entries, which are encrypted on the phone.</li>
            <li>Your PIN, duress PIN, trigger settings and protection schedule.</li>
            <li>
              The sound your microphone hears while protection is on. It is analysed on the phone to detect
              your trigger phrase or a scream, and is not saved or sent anywhere.
            </li>
            <li>
              Your emergency contacts’ names and phone numbers, except in the two cases listed in the next
              section.
            </li>
          </ul>

          <h2>What is sent to our server</h2>
          <h3>When you install and use the app</h3>
          <ul>
            <li>An anonymous registration for your phone, and the name you choose to show in alerts.</li>
            <li>A push notification token, so alerts can reach your phone when the app is closed.</li>
            <li>Your guardian code and the links between you and your guardians.</li>
          </ul>
          <h3>During an SOS</h3>
          <ul>
            <li>Your name and SOS message.</li>
            <li>Your location and the route you take while the SOS is active, and your battery level.</li>
            <li>The photos, audio or video recorded as evidence, with the time each was captured.</li>
          </ul>
          <h3>Only if you use these features</h3>
          <ul>
            <li>
              <strong>Check-in timer and daily commute:</strong> the deadline, your note, and the contact
              numbers to alert if you do not check in.
            </li>
            <li>
              <strong>WhatsApp alerts:</strong> the phone numbers of up to 5 contacts you add, and a record of
              the messages sent to them.
            </li>
            <li>
              <strong>Nearby helpers:</strong> if you volunteer as a helper, your recent location, so that
              you can be alerted to an SOS close to you.
            </li>
            <li>
              <strong>Family circle:</strong> if you switch on location sharing, your last location and
              battery level, visible to your guardians.
            </li>
            <li>
              <strong>Safety map:</strong> the place and category you report. Reports are shown without your
              name.
            </li>
            <li>
              <strong>Community:</strong> the posts and replies you write. They are shown under a made-up
              name that changes in every conversation.
            </li>
          </ul>

          <h2>Why the app asks for each permission</h2>
          <ul>
            <li>
              <strong>Location, including in the background:</strong> to send your position during an SOS,
              check-in or journey, even when the app is not open on screen.
            </li>
            <li>
              <strong>Microphone:</strong> to detect your trigger phrase or a scream on the phone, and to
              record audio evidence during an SOS.
            </li>
            <li>
              <strong>Camera:</strong> to capture photo or video evidence during an SOS, and to scan a number
              plate in cab mode.
            </li>
            <li>
              <strong>SMS:</strong> to send your alert message and location to your emergency contacts. The
              app does not read your messages.
            </li>
            <li>
              <strong>Phone:</strong> to call your emergency contacts and helplines, and to notice a SIM
              change or shutdown so your contacts can be told.
            </li>
            <li>
              <strong>Contacts:</strong> to let you pick emergency contacts from your address book. Your
              address book is not uploaded.
            </li>
            <li>
              <strong>Accessibility service:</strong> only to detect the volume-key SOS pattern while the
              screen is off or locked. Naari Kavach does not read what is on your screen.
            </li>
            <li>
              <strong>Bluetooth and nearby Wi-Fi devices:</strong> for a paired panic button, headset button
              and the optional offline alert to phones close by.
            </li>
            <li>
              <strong>Notifications, display over other apps and battery settings:</strong> to show
              full-screen alarms and keep protection running in the background.
            </li>
          </ul>
          <p>
            Every permission can be refused or turned off in Android settings. The features that depend on it
            will then stop working.
          </p>

          <h2>Who receives your data</h2>
          <ul>
            <li>
              <strong>Your emergency contacts</strong> receive your message and location by SMS through your
              mobile operator, and phone calls from your phone.
            </li>
            <li>
              <strong>Anyone who has your tracking link</strong> can see your location, route, battery level
              and evidence while the SOS is active. Share the link only with people you trust.
            </li>
            <li>
              <strong>Your guardians</strong> receive your SOS alerts, and your location if you have switched
              on sharing.
            </li>
            <li>
              <strong>Nearby helpers</strong> who have volunteered receive your SOS location. You can turn
              this off in the app.
            </li>
          </ul>
          <p>We use these services to deliver alerts and show maps. Each receives only what it needs:</p>
          <ul>
            <li>Google Firebase Cloud Messaging, to deliver push notifications.</li>
            <li>WhatsApp Business (Meta) and Twilio, to send alerts to the contacts you listed.</li>
            <li>
              Telegram and your email provider, only if you set up Telegram or email alerts yourself.
            </li>
            <li>
              Google Maps, OpenStreetMap and Esri, to show maps and find safe places near your location.
            </li>
            <li>Our hosting and database providers, which store the data described above.</li>
          </ul>
          <p>
            We may disclose data when the law requires it, for example in response to a valid legal order.
          </p>

          <h2>How long we keep it, and how to delete it</h2>
          <ul>
            <li>
              A tracking link stops showing your location and evidence 24 hours after the SOS ends.
            </li>
            <li>
              Data on our server is kept until you delete it. Open the app’s settings and choose{" "}
              <strong>Delete my cloud data</strong>. This removes your incidents, locations, evidence files,
              check-ins, guardian links and notifications from the server.
            </li>
            <li>Uninstalling the app removes everything stored on your phone, including your diary.</li>
            <li>
              Messages already delivered to your contacts by SMS, WhatsApp, Telegram or email stay with those
              recipients and services, and we cannot recall them.
            </li>
          </ul>

          <h2>How we protect it</h2>
          <ul>
            <li>Data travels between the app and our server over an encrypted connection.</li>
            <li>Tracking links are long random addresses that cannot be guessed.</li>
            <li>Evidence files are served only through links that expire after 15 minutes.</li>
            <li>Your diary is encrypted with a key held in your phone’s secure hardware.</li>
          </ul>
          <p>No system is completely secure, and we cannot guarantee that data will never be accessed unlawfully.</p>

          <h2>Children</h2>
          <p>
            Naari Kavach is meant for adults. If you are under 18, use it only with the permission of a parent
            or guardian.
          </p>

          <h2>Your rights</h2>
          <p>
            You can ask us what data we hold about you, ask us to correct or delete it, and withdraw your
            consent at any time by deleting your cloud data or uninstalling the app. These rights are provided
            under India’s Digital Personal Data Protection Act, 2023.
          </p>

          <h2>Changes to this policy</h2>
          <p>
            If we change this policy, we will publish the new version on this page and update the date at the
            top.
          </p>

          <h2>Contact us</h2>
          {CONTACT_EMAIL ? (
            <p>
              For questions, complaints or a deletion request, write to{" "}
              <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>.
            </p>
          ) : (
            <p>
              To delete your data, use <strong>Delete my cloud data</strong> in the app’s settings.
            </p>
          )}
        </div>
      </main>

      <footer className="s-footer">
        <div className="s-wrap">
          <p>
            <strong>In immediate danger, call 112.</strong> Naari Kavach is an aid, not a replacement for
            emergency services.
          </p>
          <p>
            © {new Date().getFullYear()} Naari Kavach · <a href="/">Home</a>
          </p>
        </div>
      </footer>
    </div>
  );
}
