// Morceaux français du journal (appels Logger.xxx du code Kotlin) qui
// manquent à app/src/main/assets/logs/en.json : à traduire et ajouter,
// pour que le journal s'affiche dans la langue de l'app (LogText).
// Usage : node tools/i18n/log-fragments.mjs
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(here, '..', '..')
const src = path.join(repo, 'app', 'src', 'main', 'java')
const table = JSON.parse(fs.readFileSync(path.join(repo, 'app', 'src', 'main', 'assets', 'logs', 'en.json'), 'utf8'))
const BS = String.fromCharCode(92)
const found = new Map()

const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => {
  const p = path.join(d, e.name)
  if (e.isDirectory()) return walk(p)
  if (!p.endsWith('.kt')) return
  const text = fs.readFileSync(p, 'utf8')
  const re = /Logger\.(debug|info|success|warn|error)\(/g
  while (re.exec(text)) {
    // Arguments jusqu'à la parenthèse fermante ; littéraux relevés (le
    // premier est l'étiquette, laissée telle quelle).
    let i = re.lastIndex
    let depth = 1
    const lits = []
    while (i < text.length && depth > 0) {
      const c = text[i]
      if (c === '"') {
        let j = i + 1
        let s = ''
        let brace = 0
        while (j < text.length) {
          const d2 = text[j]
          if (d2 === BS) { s += text[j] + text[j + 1]; j += 2; continue }
          if (d2 === '$' && text[j + 1] === '{') { brace++; s += '${'; j += 2; continue }
          if (d2 === '}' && brace > 0) { brace--; s += '}'; j++; continue }
          if (d2 === '"' && brace === 0) break
          s += d2
          j++
        }
        lits.push(s)
        i = j + 1
        continue
      }
      if (c === '(') depth++
      if (c === ')') depth--
      i++
    }
    for (const lit of lits.slice(1)) {
      for (const f of lit.split(/\$\{[^}]*\}|\$[a-zA-Z_][a-zA-Z0-9_.]*/)) {
        const t = f.trim()
        if (/[a-zà-ÿ]{2,}/i.test(t) && !(t in table)) found.set(t, path.relative(repo, p))
      }
    }
  }
})
walk(src)
for (const [k, file] of [...found.entries()].sort()) console.log(`${JSON.stringify(k)}  (${file})`)
console.error(`${found.size} morceau(x) sans traduction`)
