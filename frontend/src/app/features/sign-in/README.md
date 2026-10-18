# features/sign-in

The sign-in screen: username/password form, calls `/auth/login`, stores the tokens through
`core/auth`, and shows a readable message on a refused sign-in (clearing the password and focusing it
for the retry). The only route that does not need a session; a signed-in user who opens it is sent
on by `guestGuard`, and a successful sign-in replaces this page in the browser history, so Back does
not return to the form.

It follows the `returnUrl` query parameter after signing in only when it is a path on this origin,
and shows "your session has expired" when the interceptor sent the user here with `reason=expired`.
