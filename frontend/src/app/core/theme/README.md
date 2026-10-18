# core/theme

`theme.service.ts`: the light/dark theme as a signal, reflected onto `<html>` as a `dark` class that
the stylesheet's custom properties key off. The choice is kept in `localStorage`; it is a display
preference only and never a credential.
