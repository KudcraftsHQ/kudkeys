-- CreateTable
CREATE TABLE "kudkeys_user" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "emailVerified" BOOLEAN NOT NULL DEFAULT false,
    "name" TEXT NOT NULL,
    "image" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "voiceKeywords" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "languages" TEXT[] DEFAULT ARRAY['id', 'en']::TEXT[],

    CONSTRAINT "kudkeys_user_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "kudkeys_session" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "token" TEXT NOT NULL,
    "ipAddress" TEXT,
    "userAgent" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "kudkeys_session_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "kudkeys_account" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "accountId" TEXT NOT NULL,
    "providerId" TEXT NOT NULL,
    "accessToken" TEXT,
    "refreshToken" TEXT,
    "idToken" TEXT,
    "accessTokenExpiresAt" TIMESTAMP(3),
    "refreshTokenExpiresAt" TIMESTAMP(3),
    "scope" TEXT,
    "password" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "kudkeys_account_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "kudkeys_verification" (
    "id" TEXT NOT NULL,
    "identifier" TEXT NOT NULL,
    "value" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "kudkeys_verification_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "voice_session" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "session" TEXT NOT NULL,
    "startedAt" TIMESTAMP(3) NOT NULL,
    "endedAt" TIMESTAMP(3) NOT NULL,
    "audioSeconds" DOUBLE PRECISION NOT NULL,
    "finals" INTEGER NOT NULL,
    "outcome" TEXT NOT NULL,
    "model" TEXT,

    CONSTRAINT "voice_session_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "kudkeys_user_email_key" ON "kudkeys_user"("email");

-- CreateIndex
CREATE UNIQUE INDEX "kudkeys_session_token_key" ON "kudkeys_session"("token");

-- CreateIndex
CREATE INDEX "kudkeys_session_userId_idx" ON "kudkeys_session"("userId");

-- CreateIndex
CREATE INDEX "kudkeys_account_userId_idx" ON "kudkeys_account"("userId");

-- CreateIndex
CREATE INDEX "kudkeys_verification_identifier_idx" ON "kudkeys_verification"("identifier");

-- CreateIndex
CREATE INDEX "voice_session_userId_startedAt_idx" ON "voice_session"("userId", "startedAt");

-- AddForeignKey
ALTER TABLE "kudkeys_session" ADD CONSTRAINT "kudkeys_session_userId_fkey" FOREIGN KEY ("userId") REFERENCES "kudkeys_user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "kudkeys_account" ADD CONSTRAINT "kudkeys_account_userId_fkey" FOREIGN KEY ("userId") REFERENCES "kudkeys_user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "voice_session" ADD CONSTRAINT "voice_session_userId_fkey" FOREIGN KEY ("userId") REFERENCES "kudkeys_user"("id") ON DELETE CASCADE ON UPDATE CASCADE;
