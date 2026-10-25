# Watermark editor refinement

The current control draws a manual oval and previews horizontal interpolation.
It has no detector, learned templates, confidence score or clean-image recovery.
The supplied 249-rule corpus contains no watermark annotations. The reader-rule
save path has no live reporting endpoint, transmission opt-in, queue, retry worker
or server receipt; rules remain private local data unless exported by the user.

Code inspection identifies specific interaction gaps in `ReaderImageMaskDialog`:

- Every first pointer resets the oval, including the beginning of a pinch. When
  a pinch returns to one finger, the remaining pointer can move the selection.
- There are no persistent selection move/resize handles, magnifier, before/after
  toggle or selection undo. A new gesture also clears the preview.
- Preview uses a global busy state that disables interaction and dismissal.
  It has no independently cancellable preview request or stale-result token.
- The selection and viewport are remembered only for the current composition;
  rotation/recreation, keyboard and accessibility editing need explicit support.

## Concrete implementation sequence

1. Store selection in source-image coordinates, with one explicit invertible
   source-to-viewport transform shared by drawing, hit testing and export. Model
   select, move, resize, pinch/pan and preview as separate interaction states.
   A pinch never starts or changes a selection, including after one finger lifts.
2. Add visible move/resize handles, minimum touch targets, a precise magnifier,
   reset/undo and a before/after toggle. Preserve selection and viewport across
   rotation; provide labelled keyboard and accessibility adjustment actions.
3. Run preview in a cancellable worker keyed to image and selection generation.
   Keep the original visible and gestures/cancel responsive. Discard old results,
   debounce edits, show explicit progress/errors and never write the chapter file.
   Apply saves only the previewed selection; Cancel saves nothing; Undo restores
   the exact original and its previous rule.
4. Add optional local watermark *detection* only after obtaining representative
   marked overlays and retained clean regions. Start with explicit template
   suggestions, show the proposed mask and an honest confidence/ambiguity state,
   and require confirmation. No automatic whole-page removal or online upload.
   Evaluate transparent text, logos, changing backgrounds, compression, scaling
   and lookalike dialogue/line art with held-out examples and negative controls.
5. Keep reconstruction separate. Interpolation is a guess. Semi-transparent
   inversion requires an identified overlay and alpha model or aligned clean
   references; one marked unknown background does not establish either. Display
   method/limitations alongside the preview and retain a reversible original.

## Acceptance checks

Automated gesture tests must cover multi-pointer handoff, zoom/pan, all corners,
rotation/recreation, very tall images, source boundaries and accessibility steps.
Preview tests must delay and reorder results, cancel during decoding, switch
images, retry errors and verify byte-for-byte preservation outside the mask.
Apply/Cancel/Undo tests must verify saved selection, original bytes and immediate
reader refresh. Detection tests need independently labelled positives/negatives;
the current spam corpus cannot establish watermark accuracy. Finish with visual
QA of handle visibility, contrast, progress, focus order and before/after states.

This is the scoped plan for further work, not a claim that detection or these
interactions are already implemented. Urgent reader fixes can ship independently.
