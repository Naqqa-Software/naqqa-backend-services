# Angular setup (@naqqa/auth)

## Getting the library

`@naqqa/auth` lives in the naqqa-ui workspace (`projects/auth`). It is vendored into apps from the theme lineage of naqqa-ui: build the libraries in dependency order, then consume them from `dist/`.

```
cd naqqa-ui
node scripts/build-libs.mjs auth
```

The script computes `@naqqa/*` dependencies from source imports and builds them topologically into `dist/`. Point your app's `tsconfig.json` `paths` at `dist/auth` (or install the packed output). Peer dependencies: `@angular/common` and `@angular/core` ^21.

## Configuration

```ts
providers: [
  { provide: AUTH_CONFIG, useValue: {
      apiUrl: 'https://api.example.com/api',
      clientType: 'WEB',
      sso: {
        methods: { passkey: true, magicLink: true },
        magicLinkRedirect: '/dashboard',
        stepUp: true,
        oidcBaseUrl: 'https://api.example.com',
        webauthn: { conditionalUi: true, autoPrompt: false },
      },
  } },
]
```

`apiUrl` is the API root; the library appends `/auth` itself, so `/auth/login` becomes `https://api.example.com/api/auth/login`. `sso.oidcBaseUrl` is the origin that serves the OIDC provider endpoints (`/oauth2/...`, which live at the server root, not under `/api/auth`); when omitted it defaults to the origin of `apiUrl`. `sso.methods` can only narrow what the server reports at `GET /api/auth/methods`. `sso.endpoints` overrides any path (keys such as `magicLinkConsume`, `webauthnLoginVerify`, `sessions`). `credentialsFor` decides which URLs get cookies and 401 refresh.

## Routes

Add routes for the security settings page (`SecuritySettingsComponent`, selector `auth-security-settings`: factors, passkeys, sessions) and the magic link landing page (`MagicLinkConsumeComponent`, selector `auth-magic-link-consume`). The landing route must match the link the server emails and must be a path your app serves, for example `/auth/magic-link`.

```ts
{ path: 'account/security', loadComponent: () => import('@naqqa/auth').then(m => m.SecuritySettingsComponent) },
{ path: 'auth/magic-link', loadComponent: () => import('@naqqa/auth').then(m => m.MagicLinkConsumeComponent) },
```

Check the exported component names in `projects/auth/src/public-api.ts` for your version.

## Using your app as an OIDC sign-in front end

When the provider needs the user to sign in or consent, `/oauth2/authorize` redirects the browser to your frontend: `<frontend.url>/login?oidc=<handle>`, `<frontend.url>/consent?oidc=<handle>` and `<frontend.url>/device`. These paths can be overridden in `naqqa.sso.oidc-provider` (`login-url`, `consent-url`, `device-url`), for example to `/auth/login`.

- Login: `LoginComponent` and `LoginFormComponent` detect the `oidc` query parameter. After any successful sign in (password, passkey, code, MFA) they navigate the browser to `<oidcBaseUrl>/oauth2/authorize/resume?handle=<handle>`, and the browser follows the redirect to the client. Without `oidc` they navigate to the portal as before. The handle must be 43 URL safe characters, otherwise it is ignored.
- Consent: `OidcConsentComponent` (`auth-oidc-consent`) loads `GET /oauth2/interaction`, shows the client name and requested scopes in Romanian, Russian or English, and posts the decision to `/oauth2/authorize/consent`.
- Device code: `OidcDeviceComponent` (`auth-oidc-device`) takes the user code (shown as `XXXX-XXXX`, prefilled from `?user_code=`), calls `GET /oauth2/device/lookup`, shows the client name and posts approve or deny to `/oauth2/device/decision`. The user must already be signed in.

```ts
{ path: 'consent', loadComponent: () => import('@naqqa/auth').then(m => m.OidcConsentComponent) },
{ path: 'device', loadComponent: () => import('@naqqa/auth').then(m => m.OidcDeviceComponent) },
```

The provider endpoints use cookies, so the frontend and the API must be same-site (or use `SameSite=None`), and the consent decision is a cross-document POST that needs the browser to send the session and binding cookies.

## Lazy loading

Keep the initial bundle small: load only the login page eagerly, and lazy load security settings, MFA enrollment and WebAuthn code with `loadComponent`. The WebAuthn service touches `navigator.credentials` only when called, so importing it costs no start-up work.

## SSR notes

- Browser APIs (`navigator.credentials`, `localStorage`, `window`) are unavailable on the server. Guard with `isPlatformBrowser` and never start passkey prompts during server rendering.
- Forward the incoming cookie header to the API when rendering authenticated pages on the server, and keep `apiUrl` reachable from the server.
- Do not render the magic link consume page on the server; the token must be consumed in the browser that requested it.
