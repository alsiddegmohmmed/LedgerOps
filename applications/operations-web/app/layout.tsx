import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "LedgerOps Operations",
  description: "Authenticated LedgerOps Operations Web shell",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
