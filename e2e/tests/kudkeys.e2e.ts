import { expect, type Screen } from 'e2e'
import { test } from '@e2e-dev/web' // the same test, typed with the web engine's `browser` fixture
import { APP_URL, as, USERS } from '../stack'

// Fixture stack: server/scripts/e2e-stack.ts, on the run's port block (../stack.ts). /as/<user> signs in and redirects.
// owner's data is only ever read; checks that change something use editor (or devices), so the order never matters.

/** A signed-in page has drawn: its heading, the tab bar, and no error note or empty skeleton left. */
async function loaded(screen: Screen, title: string) {
  await expect(screen.getByRole('heading', title, { level: 1 })).toBeVisible()
  await expect(screen.getByRole('link', /^Words$/)).toBeVisible()
  await expect(screen.getByText(/unauthorized|internal|not_found|Something went wrong/)).toHaveCount(0)
}

test('the sign-in form refuses a wrong password, then signs in to the profile', async ({ app, screen, browser }) => {
  await app.open(`${APP_URL}/login`)
  await expect(screen.getByRole('heading', /^Sign in$/)).toBeVisible()
  await screen.getByLabel('Email').fill(USERS.owner.email)
  await screen.getByLabel('Password').fill('not-the-password')
  await screen.getByRole('button', /^Sign in$/).click()
  await expect(screen.getByText('Wrong email or password.')).toBeVisible()
  await expect(browser).toHaveURL(/\/login$/)
  await screen.getByLabel('Password').fill(USERS.owner.password)
  await screen.getByRole('button', /^Sign in$/).click()
  await loaded(screen, 'Profile')
  await expect(browser).toHaveURL(/127\.0\.0\.1:\d+\/$/)
  await expect(screen.getByText(USERS.owner.email).first()).toBeVisible()
  await expect(screen.getByRole('button', `Sign out ${USERS.owner.email}`)).toBeVisible()
})

test('profile: a new voice keyword is saved and still there after a reload', async ({ app, screen, browser }) => {
  await app.open(as('editor', '/'))
  await loaded(screen, 'Profile')
  // seeded keywords
  await expect(screen.getByRole('button', 'Remove Fenno')).toBeVisible()
  await expect(screen.getByRole('button', 'Remove TypeMap')).toBeVisible()
  const draft = screen.getByPlaceholder('Add another…')
  await draft.fill('Kudcrafts')
  await draft.press('Enter')
  await expect(screen.getByRole('button', 'Remove Kudcrafts')).toBeVisible()
  await screen.getByRole('button', /^Save$/).click()
  await expect(screen.getByText(/^Saved\. The keyboard uses it/)).toBeVisible()
  await browser.reload()
  await loaded(screen, 'Profile')
  await expect(screen.getByRole('button', 'Remove Kudcrafts')).toBeVisible()
  await expect(screen.getByRole('button', 'Remove Fenno')).toBeVisible()
})

test('usage: the voice sessions are listed with their numbers', async ({ app, screen }) => {
  await app.open(as('owner', '/usage'))
  await loaded(screen, 'Usage')
  await expect(screen.getByText('This week')).toBeVisible()
  await expect(screen.getByText('This month')).toBeVisible()
  // four seeded sessions plus the header row
  await expect(screen.getByRole('row')).toHaveCount(5)
  await expect(screen.getByText('client closed')).toBeVisible()
  await expect(screen.getByText('No voice sessions yet', { exact: false })).toHaveCount(0)
})

test('words: search finds a typed word, and removed words have their own list', async ({ app, screen }) => {
  await app.open(as('owner', '/words'))
  await loaded(screen, 'Words')
  await expect(screen.getByRole('cell', /^glorbix/).first()).toBeVisible()
  await screen.getByRole('textbox', 'Search words').fill('zibble')
  await expect(screen.getByRole('cell', /^zibbleton/)).toBeVisible()
  await expect(screen.getByRole('cell', /^glorbix/)).toHaveCount(0)
  await screen.getByRole('textbox', 'Search words').fill('')
  await screen.getByRole('button', /^Removed$/).click()
  await expect(screen.getByRole('button', 'Restore grumbleflax')).toBeVisible()
  await expect(screen.getByRole('cell', /^glorbix/)).toHaveCount(0)
})

test('words: an added word can be removed and shows under Removed', async ({ app, screen }) => {
  await app.open(as('editor', '/words'))
  await loaded(screen, 'Words')
  await screen.getByRole('textbox', 'Word').fill('zentrafel')
  await screen.getByRole('button', /Add$/).click()
  await expect(screen.getByText(/^Added “zentrafel”/)).toBeVisible()
  await screen.getByRole('textbox', 'Search words').fill('zentrafel')
  await screen.getByRole('button', 'Remove zentrafel').click()
  await expect(screen.getByText(/^Removed “zentrafel”/)).toBeVisible()
  await screen.getByRole('button', /^Removed$/).click()
  await expect(screen.getByRole('button', 'Restore zentrafel')).toBeVisible()
})

test('backups: the phone backups are listed and one can be deleted', async ({ app, screen }) => {
  await app.open(as('editor', '/backups'))
  await loaded(screen, 'Backups')
  await expect(screen.getByRole('link', /Download/)).toHaveCount(5)
  await expect(screen.getByText('Manual', { exact: true })).toBeVisible()
  await expect(screen.getByText(/Pixel 8/).first()).toBeVisible()
  await screen.getByRole('button', 'Delete backup').first().click()
  // asks first: Keep / Delete
  await expect(screen.getByRole('button', /^Keep$/)).toBeVisible()
  await screen.getByRole('button', /^Delete$/).click()
  await expect(screen.getByText('Backup deleted.')).toBeVisible()
  await expect(screen.getByRole('link', /Download/)).toHaveCount(4)
})

test('devices: the phone keyboard is listed and can be signed out', async ({ app, screen }) => {
  await app.open(as('devices', '/devices'))
  await loaded(screen, 'Devices')
  await expect(screen.getByText('This browser')).toBeVisible()
  await expect(screen.getByText('Kud Keys keyboard on Android')).toBeVisible()
  // the only other session is the phone's; this browser has no Sign out button of its own here
  await expect(screen.getByRole('button', /^Sign out$/)).toHaveCount(1)
  await screen.getByRole('button', /^Sign out$/).click()
  await expect(screen.getByText('Signed out on that device.')).toBeVisible()
  await expect(screen.getByText('Kud Keys keyboard on Android')).toHaveCount(0)
  await expect(screen.getByText('This browser')).toBeVisible()
})

test('signing out goes back to the sign-in page', async ({ app, screen, browser }) => {
  await app.open(as('editor', '/usage'))
  await loaded(screen, 'Usage')
  await screen.getByRole('button', `Sign out ${USERS.editor.email}`).click()
  await expect(screen.getByRole('heading', /^Sign in$/)).toBeVisible()
  await expect(browser).toHaveURL(/\/login$/)
  // and the session is gone: a signed-in page now sends the browser back to sign-in
  await browser.goto(`${APP_URL}/words`)
  await expect(screen.getByRole('heading', /^Sign in$/)).toBeVisible()
})
