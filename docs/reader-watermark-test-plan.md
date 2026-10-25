# Watermark editor regression cases

These are the implementation acceptance cases, not passing tests or evidence of
trained detection. Use synthetic overlays in the test APK. The private spam
export has no watermark labels and is unsuitable for measuring detection recall.

## Fixtures and measurable interaction checks

Use a 600 x 1800 synthetic page with known coloured pixels, a 1200 x 12000 tall
page, and a 64 x 64 small image. Overlay text and a logo with known alpha at
0.15, 0.35 and 0.65 on flat, textured and line-art backgrounds. Keep the clean
reference in tests only. None of these fixtures establish real-world accuracy.

| Case | Actions | Required assertion |
| --- | --- | --- |
| Pinch on saved selection | Start two fingers inside and outside the oval, zoom, lift either finger, move the remaining finger, lift | Source selection and preview generation remain identical; only viewport changes |
| Single-finger drawing | Drag in each of four directions, lift, then start a new draw | Bounds normalize and clamp in source pixels; finger-up adds no movement |
| Move and resize | Drag interior and each handle at 1x and 12x zoom, including screen edges | Move preserves size; resize preserves opposite anchor; bounds stay valid and minimum size is enforced |
| Transform consistency | Pan and zoom, then select a known source-pixel marker | Overlay, hit test, preview mask and persisted mask identify the same pixels within one source pixel |
| Recreation | Select, zoom, rotate and recreate activity | Selection, viewport and method survive; no rule is saved before Apply |
| Accessibility | Focus every control and adjust every edge by one pixel | Spoken bounds use source pixels, focus order is stable, actions work without touch |
| Before/after | Toggle repeatedly during and after preview | Viewport and mask remain fixed; original is always available |

## Preview and persistence checks

Use a fake renderer with independent latches for requests A and B. Complete B
before A. Only B may become visible or enable Apply. Change selection, change
image, cancel, rotate and retry an error while A is pending; late A must never
publish into the new state. Cancel must remain available while decoding. Workers
must close streams and release bitmaps on success, cancellation and error.

Apply must persist exactly the source coordinates and method of the displayed
preview, update the current reader holder, and retain original bytes. Cancel
must not create or edit a rule. Undo must restore the previous rule and exact
original stream. Compare every pixel outside the ellipse and compare the encoded
original before and after all operations. Exercise adjacent crop rules so preview
coordinates never drift into the already-cropped coordinate system.

## Local detection gate

Before automatic suggestions, collect explicit positive masks and independently
labelled clean regions with user consent. Split by watermark identity and series,
not neighbouring pages. Test scaling, translucency, changing backgrounds, JPEG
artifacts, dialogue, credits, sound effects and signatures as separate strata.
Report proposals, misses, false proposals and user corrections with denominators;
keep ambiguous proposals visible and require confirmation. Never infer that a
whole page is spam from a watermark proposal. No network transmission is needed.

Interpolation and an estimated overlay inversion need separate pixel-error and
outside-mask preservation tests against synthetic clean references. Call either
result a reconstruction estimate. A single marked unknown background cannot
prove the original pixels or supply an identified alpha model.

Finish with screenshots at light/dark themes and several font/display scales,
manual TalkBack focus review, touch-target checks and cancellation responsiveness
on a tall page. A completed gesture suite does not substitute for a detector
evaluation on real labelled watermark examples.
