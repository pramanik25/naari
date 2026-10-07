import "./globals.css";

export const metadata = {
  title: "Naari Shakti — women's safety app for Android",
  description:
    "Naari Shakti sends an SOS with your live location to the people you trust, records evidence, and keeps working when you can't reach your phone. Free Android download.",
};

export const viewport = {
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
