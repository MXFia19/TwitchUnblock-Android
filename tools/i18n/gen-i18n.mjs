// Textes de l'app Android, repris des dictionnaires de l'app iPhone et de
// l'Apple TV (Constants.swift, RussianStrings.swift, TVStrings.swift), plus
// ceux propres à Android (android-strings.json, prioritaires).
// Usage : node tools/i18n/gen-i18n.mjs <dépôt iOS>
// Écrit app/src/main/assets/i18n/{fr,en,es,ru}.json, puis vérifie que chaque
// clé utilisée dans le code Kotlin existe en anglais.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ios = process.argv[2]
if (!ios) { console.error('Usage : node tools/i18n/gen-i18n.mjs <dépôt iOS>'); process.exit(2) }
const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(here, '..', '..')
const outDir = path.join(repo, 'app', 'src', 'main', 'assets', 'i18n')
const LANGS = ['fr', 'en', 'es', 'ru']

const unescapeSwift = (s) => s
  .replace(/\\u\{([0-9a-fA-F]+)\}/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
  .replace(/\\(.)/g, (_, c) => ({ n: '\n', t: '\t', r: '\r', 0: '\0' })[c] ?? c)

function entries(text) {
  const re = /"((?:[^"\\]|\\.)*)"\s*:\s*"((?:[^"\\]|\\.)*)"/g
  const out = {}
  let m
  while ((m = re.exec(text))) out[unescapeSwift(m[1])] = unescapeSwift(m[2])
  return out
}

/** Blocs « .xx: [ … » d'un fichier, dans l'ordre où ils apparaissent. */
function blocks(text, langs) {
  const marks = langs.map((l) => {
    const i = text.search(new RegExp(`\\n\\s*\\.${l}:\\s*\\[`))
    return { l, i }
  }).filter((m) => m.i >= 0).sort((a, b) => a.i - b.i)
  const out = {}
  marks.forEach((m, k) => {
    const end = k + 1 < marks.length ? marks[k + 1].i : text.length
    out[m.l] = entries(text.slice(m.i, end))
  })
  return out
}

const read = (p) => fs.readFileSync(path.join(ios, p), 'utf8')
const constants = read('Sources/Constants.swift')
const start = constants.indexOf('private let translations:')
const ruMark = constants.indexOf('.ru: kRussianStrings')
const main = blocks(constants.slice(start, ruMark > 0 ? ruMark : undefined), ['fr', 'en', 'es'])
main.ru = entries(read('Sources/Localization/RussianStrings.swift'))
const tv = blocks(read('TV/TVStrings.swift'), LANGS)
const android = JSON.parse(fs.readFileSync(path.join(here, 'android-strings.json'), 'utf8'))

fs.mkdirSync(outDir, { recursive: true })
const tables = {}
for (const l of LANGS) {
  const t = { ...(main[l] || {}), ...(tv[l] || {}) }
  for (const [k, v] of Object.entries(android)) if (v[l] != null) t[k] = v[l]
  tables[l] = t
  const keys = Object.keys(t).sort()
  fs.writeFileSync(path.join(outDir, `${l}.json`),
    '{\n' + keys.map((k) => `  ${JSON.stringify(k)}: ${JSON.stringify(t[k])}`).join(',\n') + '\n}\n')
  console.log(`${l} : ${keys.length} textes`)
}

// Clés utilisées par le code : t("…"), tn("…", …), et la liste des clés
// passées par des variables (android-strings.json → "_indirect").
const src = path.join(repo, 'app', 'src', 'main', 'java')
const used = new Set(android._indirect?.keys ?? [])
const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => {
  const p = path.join(d, e.name)
  if (e.isDirectory()) return walk(p)
  if (!p.endsWith('.kt')) return
  const text = fs.readFileSync(p, 'utf8')
  for (const m of text.matchAll(/\b(?:t|tn|I18n\.t|I18n\.tn)\(\s*"([a-z0-9_]+)"/g)) used.add(m[1])
  for (const m of text.matchAll(/AppState\.t\(if \([^)]*\) "([a-z0-9_]+)" else "([a-z0-9_]+)"\)/g)) { used.add(m[1]); used.add(m[2]) }
})
walk(src)
const missing = [...used].filter((k) => !(k in tables.en)).sort()
const untranslated = LANGS.flatMap((l) => [...used].filter((k) => k in tables.en && !(k in tables[l])).map((k) => `${l}:${k}`))
console.log(`${used.size} clés utilisées`)
if (missing.length) { console.error('ABSENTES : ' + missing.join(', ')); process.exitCode = 1 }
if (untranslated.length) console.warn('Sans traduction (repli anglais) : ' + untranslated.join(', '))
