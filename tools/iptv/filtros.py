"""What goes into CodeTV: the rules shared by the scripts in this folder.

Only free channels: public broadcasters, free news channels and official FAST channels. Pay-TV brands are dropped by
name (in public lists they are unauthorized restreams), and so are Pluto TV feeds (they bring Pluto's own ads).
"""
import re

# Pay-TV brands: in public lists these are restreams without permission, never official free streams.
PAY = re.compile(
    r"\b(ESPN|Fox Sports|TyC|DAZN|beIN|Sky Sports?|SporTV|Premiere|Combate|TNT|HBO|Telecine|Star\+|"
    r"Disney|A&E|AMC|AXN|Cartoon|Discovery|History|Lifetime|Sony (Channel|Movies)|Studio Universal|"
    r"Universal TV|Warner|TCM|Comedy Central (Brasil|Latin)|Nickelodeon \(|Paramount Network|"
    r"National Geographic|Nat ?Geo|Animal Planet|Golf Channel|Eurosport|Sport ?TV|Win Sports|DirecTV|"
    r"Movistar|Claro Sports|TUDN|Rede Globo|Globo ?News|SIC|TVI|CNN Portugal|Canal\+|Gol ?TV|Cinemax|"
    r"Film ?& ?Arts|Syfy|USA Network|Fox Channel|FX\b|Space\b|E! |Golden|Telemundo|Univision|ViX|"
    r"Max\b|Paramount\+|NBA TV|NFL Network|MLB Network|Tennis Channel|Fox Deportes|TNT Sports|"
    r"Arena Sport|Setanta|Sportklub|SuperSport|NHL Network|ACC Network$|Tigo Sports|MUTV)", re.I)
# Pluto TV (by name or by its feed address).
PLUTO = re.compile(r"pluto|/plu-", re.I)
# Part-time channels and anything below 400p.
JUNK = re.compile(r"\[Not 24/7\]|\((1[0-9]{2}|2[0-9]{2}|3[0-9]{2})p\)", re.I)
# Sports channels kept: from these countries (tvg-id ends in .br@, .us@, ...) or international.
SPORTS_COUNTRIES = re.compile(r"\.(br|pt|ar|mx|co|cl|pe|es|us|uk|int|ec|ve|uy|py|bo)@|^$", re.I)

# The playlists downloaded from iptv-org, by section ("countries/br.m3u" is saved as countries_br.m3u).
SOURCES = {
    "Brasil": ["countries/br"],
    "Portugal": ["countries/pt"],
    "Latinos": ["countries/ar", "countries/mx", "countries/co", "countries/cl", "countries/pe"],
    "Esportes": ["categories/sports"],
}
# Latin channels are split by country (from tvg-id).
REGION = {"br": "Brasil", "pt": "Portugal", "ar": "Argentina", "cl": "Chile", "co": "Colômbia", "mx": "México",
          "pe": "Peru"}
ORDER = ["Brasil", "Portugal", "Argentina", "Chile", "Colômbia", "México", "Peru", "Esportes"]
# iptv-org categories, in Portuguese.
CATEGORY = {"news": "Notícias", "entertainment": "Entretenimento", "general": "Variedades", "movies": "Filmes",
            "music": "Música", "kids": "Infantil", "sports": "Esportes", "documentary": "Documentários",
            "education": "Educação", "lifestyle": "Estilo de vida", "cooking": "Culinária", "business": "Negócios",
            "culture": "Cultura", "comedy": "Comédia", "series": "Séries", "animation": "Animação",
            "classic": "Clássicos", "legislative": "Legislativo", "outdoor": "Natureza", "science": "Ciência",
            "travel": "Viagem", "weather": "Tempo", "family": "Família", "auto": "Automóveis", "relax": "Relax",
            "interactive": "Interativo", "public": "Pública"}
SKIP_GROUPS = {"xxx", "religious", "shop"}
