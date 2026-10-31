import { timingSafeEqual } from "node:crypto";

/** True when the request carries `Authorization: Bearer <one of tokens>`. */
export function authorized(req: Request, tokens: string[]): boolean {
  const header = req.headers.get("authorization") ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(header);
  if (!match) return false;
  const given = Buffer.from(match[1]!.trim());
  let ok = false;
  // Compare against every token so timing doesn't reveal which one matched.
  for (const t of tokens) {
    const want = Buffer.from(t);
    if (want.length === given.length && timingSafeEqual(want, given)) ok = true;
  }
  return ok;
}
