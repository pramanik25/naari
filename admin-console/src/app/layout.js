import "./globals.css";

export const metadata = {
  title: "Naari Shakti Admin",
  description: "Admin console for the Naari Shakti safety app",
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
