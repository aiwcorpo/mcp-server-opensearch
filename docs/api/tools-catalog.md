# Katalog narzędzi

Narzędzia to powierzchnia publiczna serwera. Każda zmiana nazwy, opisu, schematu albo ryzyka zmienia
`definitionHash` (C6) i wymaga ponownego zatwierdzenia w gateway'u, więc przechodzi przez code review razem z
tą tabelą.

| Narzędzie (klient widzi `opensearch__<nazwa>`) | Ryzyko | Akcja (C6) | Argumenty | Uprawnienie (C9) | Poświadczenie |
| --- | --- | --- | --- | --- | --- |
| `get_record` | `READ_ONLY` | `read` | `recordId` (`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`) | `mcp-opensearch-read` | z C3, `Authorization` |

Konwencje: nazwy `snake_case`, czasownik i obiekt, bez prefiksu systemu (dokłada go gateway); opis mówi, co
narzędzie zwraca, bez instrukcji dla modelu.
