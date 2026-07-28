import { NextResponse } from "next/server";
import { authorizationUrl, createOauthTransaction } from "../../../../lib/oauth";
import { redis } from "../../../../lib/redis";

export async function GET(request: Request) {
  try {
    const transaction = createOauthTransaction();
    await redis().set(
      `operations-web:oauth:${transaction.state}`,
      JSON.stringify(transaction),
      "EX",
      300,
    );
    return NextResponse.redirect(authorizationUrl(transaction), 302);
  } catch {
    return NextResponse.json({ type: "authentication_unavailable" }, { status: 503 });
  }
}
