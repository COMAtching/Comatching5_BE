Legacy palette #f5f7fa, #18202d, #174ed6, #fff6d8. System sans 14/1.6.
:root { color-scheme: light; font: 14px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: #18202d; background: #f5f7fa; }
body { margin: 0; width: 560px; max-width: 100vw; }
main { padding: 20px; }
h1 { font-size: 21px; margin: 0 0 6px; } h1 small { color: #657083; font-size: 12px; }
h2 { font-size: 16px; } p { margin: 9px 0; }
label { display: block; margin: 10px 0; font-weight: 600; }
input, select, button { font: inherit; box-sizing: border-box; }
input, select { display: block; width: 100%; padding: 8px; border: 1px solid #aeb7c5; border-radius: 6px; background: white; color: #18202d; }
.pair { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
button { border: 0; border-radius: 6px; padding: 10px 14px; color: white; background: #174ed6; cursor: pointer; margin: 8px 0; }
button:disabled { opacity: .5; cursor: default; } :focus-visible { outline: 3px solid #edb300; outline-offset: 2px; }
.notice { background: #fff6d8; padding: 10px 12px; border-radius: 6px; color: #654500; }
.hint, details { color: #596578; font-size: 12px; }
#status { white-space: pre-wrap; } #status[data-error="true"] { color: #ab2424; }
.scroll { max-height: 250px; overflow: auto; } table { width: 100%; border-collapse: collapse; background: white; font-size: 12px; }
th, td { text-align: left; padding: 7px; border-bottom: 1px solid #d9dfe9; overflow-wrap: anywhere; }
details { margin-top: 18px; } [hidden] { display: none !important; }
