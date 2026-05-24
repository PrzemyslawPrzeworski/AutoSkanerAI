# AutoSkanerAI - MVP ideas

### Główny problem

Kupno używanego samochodu jest trudne, czasochłonne i obarczone dużym ryzykiem. Kupujący musi samodzielnie analizować ogłoszenia, porównywać parametry, sprawdzać wyposażenie, oceniać wiarygodność sprzedawcy, przygotowywać pytania oraz decydować, czy dana oferta jest warta dalszego sprawdzania.
Problem pogłębia się, ponieważ ogłoszenia często są niepełne, nieprecyzyjne albo pisane marketingowo. Sprzedawcy mogą pomijać ważne informacje, nie podawać pełnej historii auta, unikać deklaracji bezwypadkowości lub niejasno opisywać wyposażenie. Osoba kupująca musi więc poświęcić dużo czasu, żeby odróżnić realnie dobrą ofertę od oferty ryzykownej.
AutoSkaner AI ma pomóc użytkownikowi szybciej ocenić, czy konkretna oferta samochodu ma sens, jakie są jej ryzyka i jakie pytania trzeba zadać sprzedawcy przed oględzinami.


### Najważniejszy zestaw funkcjonalności

- Dodanie oferty samochodu
Użytkownik może dodać ofertę na kilka sposobów:
•	wkleić treść ogłoszenia, 
•	wkleić link do ogłoszenia, 
•	ręcznie uzupełnić najważniejsze dane, 
•	opcjonalnie dodać screen, PDF albo notatki z rozmowy ze sprzedawcą. 
W MVP najważniejsze jest, żeby aplikacja działała nawet wtedy, gdy automatyczne pobranie danych z linku się nie uda. Dlatego podstawą powinno być wklejenie treści ogłoszenia lub ręczne uzupełnienie danych.

- Ekstrakcja danych z ogłoszenia
Aplikacja wyciąga z ogłoszenia najważniejsze informacje:
•	marka, 
•	model, 
•	rocznik, 
•	cena, 
•	przebieg, 
•	paliwo, 
•	skrzynia biegów, 
•	wersja wyposażenia, 
•	deklarowane wyposażenie, 
•	informacje o historii serwisowej, 
•	informacje o bezwypadkowości, 
•	kraj pochodzenia, 
•	typ sprzedawcy. 
Dane powinny być pokazane użytkownikowi w formie uporządkowanej tabeli, żeby mógł łatwo zweryfikować, czy aplikacja dobrze zrozumiała ogłoszenie.

- Analiza wyposażenia
Aplikacja sprawdza, jakie elementy wyposażenia są obecne, nieobecne albo niejasne.
Przykładowe elementy:
•	adaptacyjny tempomat, 
•	kamera cofania, 
•	czujniki parkowania przód/tył, 
•	monitorowanie martwego pola, 
•	HUD, 
•	podgrzewane fotele, 
•	podgrzewana kierownica, 
•	CarPlay / Android Auto, 
•	automatyczna skrzynia biegów, 
•	światła LED/matrycowe, 
•	systemy bezpieczeństwa. 
Aplikacja powinna pokazać:
•	co jest potwierdzone w ogłoszeniu, 
•	czego brakuje, 
•	co wymaga dopytania sprzedawcy. 

- Analiza ryzyk oferty
Aplikacja ocenia potencjalne czerwone flagi, np.:
•	brak informacji o bezwypadkowości, 
•	niejasna historia serwisowa, 
•	ogólnikowy opis, 
•	podejrzanie niska cena, 
•	brak VIN, 
•	brak numeru rejestracyjnego, 
•	import bez dokumentacji, 
•	niespójności w opisie, 
•	sprzedawca unika konkretnych deklaracji, 
•	oferta wygląda dobrze, ale brakuje kluczowych informacji. 
Wynik powinien być pokazany jako lista ryzyk z krótkim wyjaśnieniem.

- Ocena oferty
Aplikacja generuje syntetyczną ocenę, np.:
•	warto sprawdzić, 
•	sprawdzić tylko po uzyskaniu dodatkowych informacji, 
•	wysokie ryzyko — raczej odpuścić. 
Można też dodać scoring:
Kategoria	Ocena
Kompletność ogłoszenia	7/10
Wyposażenie	8/10
Ryzyko	5/10
Opłacalność	6/10
Ogólna ocena	6.5/10

- Generator pytań do sprzedawcy
Na podstawie konkretnej oferty aplikacja generuje listę pytań, które warto zadać przed oględzinami.
Przykłady:
•	Czy auto miało jakiekolwiek naprawy blacharsko-lakiernicze? 
•	Czy może Pan/Pani potwierdzić bezwypadkowość pisemnie? 
•	Czy dostępna jest pełna historia serwisowa? 
•	Czy zgadza się Pan/Pani na sprawdzenie auta w ASO lub niezależnym serwisie? 
•	Czy wszystkie systemy bezpieczeństwa działają prawidłowo? 
•	Czy podana wersja wyposażenia zgadza się z dokumentami auta? 
•	Czy są zdjęcia uszkodzeń sprzed naprawy, jeśli auto było naprawiane? 

- Porównanie kilku ofert
Użytkownik może dodać kilka samochodów i porównać je w jednym widoku.
Porównanie może obejmować:
•	cenę, 
•	rocznik, 
•	przebieg, 
•	wyposażenie, 
•	ryzyka, 
•	kompletność informacji, 
•	ogólną ocenę, 
•	rekomendację. 
To jest bardzo ważna funkcja, bo realnie użytkownik rzadko analizuje tylko jedno auto.

- Doradca wyszukiwania
Użytkownik wpisuje swoje wymagania:
•	budżet, 
•	typ nadwozia, 
•	rocznik, 
•	maksymalny przebieg, 
•	paliwo, 
•	skrzynia, 
•	priorytety, 
•	wymagane wyposażenie. 
Aplikacja proponuje:
•	jakie modele warto rozważyć, 
•	jakich wersji wyposażenia szukać, 
•	na co uważać, 
•	jakie filtry ustawić na portalach ogłoszeniowych. 
W MVP aplikacja nie musi sama pobierać wszystkich ofert z rynku. Wystarczy, że pomoże użytkownikowi dobrze ustawić kryteria i później ocenić dodane oferty.


### Co NIE wchodzi w zakres MVP

- Automatyczne przeszukiwanie całego rynku
MVP nie powinno samodzielnie scrapować Otomoto, OLX, Gratki, mobile.de ani innych portali.
To jest trudne, niestabilne i może być problematyczne regulaminowo. Na start lepiej pozwolić użytkownikowi samodzielnie dodać oferty.
- Pełna automatyczna integracja z każdym portalem ogłoszeniowym
Aplikacja nie musi obsługiwać każdego linku z internetu.
W MVP wystarczy:
•	ręczne wklejenie treści ogłoszenia, 
•	ręczne uzupełnienie danych, 
•	ewentualnie podstawowe pobieranie danych z jednego lub dwóch źródeł, jeśli okaże się to technicznie proste. 
- Gwarancja wykrycia auta powypadkowego
Aplikacja nie może obiecywać, że wykryje, czy samochód był bity, cofnięty albo wadliwy.
Może tylko wskazać ryzyka i rzeczy do sprawdzenia. Ostateczna decyzja nadal wymaga oględzin, diagnostyki i sprawdzenia auta przez specjalistę.
- Pełna integracja z CEPiK / historiapojazdu.gov.pl
W MVP nie trzeba robić pełnej integracji z państwowymi systemami.
Wystarczy, że użytkownik może wkleić lub dodać dane z raportu, a aplikacja pomoże je zinterpretować.
- Pełna baza wszystkich wersji wyposażenia
Aplikacja nie musi od razu znać każdej wersji każdego modelu.
Na start można obsłużyć ograniczony zakres, np.:
•	Toyota Corolla, 
•	Mazda 3, 
•	Hyundai Elantra, 
•	kilka najpopularniejszych modeli kompaktowych. 
- Automatyczna wycena rynkowa
Aplikacja nie musi idealnie określać, czy cena jest rynkowa.
Może dawać ogólną ocenę na podstawie podanych danych, ale bez pełnej bazy aktualnych cen nie powinna udawać profesjonalnego systemu wyceny.
- Obsługa płatności i kont premium
W MVP nie ma potrzeby budować monetyzacji.
Najważniejsze jest działające narzędzie do analizy ofert.



### Kryteria sukcesu

Kryteria funkcjonalne
Projekt można uznać za udany, jeśli użytkownik może:
-	dodać ofertę samochodu, 
-	zobaczyć uporządkowane dane wyciągnięte z ogłoszenia, 
-	otrzymać listę brakujących lub niejasnych informacji, 
-	zobaczyć analizę ryzyk, 
-	dostać pytania do sprzedawcy, 
-	porównać kilka ofert, 
-	otrzymać końcową rekomendację. 

Kryteria jakościowe
Aplikacja powinna:
-	jasno oddzielać fakty od przypuszczeń, 
-	nie wymyślać danych, których nie ma w ogłoszeniu, 
-	pokazywać użytkownikowi, skąd pochodzi dana informacja, 
-	dawać praktyczne, konkretne pytania do sprzedawcy, 
-	pomagać szybciej odrzucać słabe oferty, 
-	nie udawać, że zastępuje mechanika albo rzeczoznawcę. 

AutoSkaner AI odnosi sukces, jeśli skraca czas wstępnej oceny ogłoszenia samochodu z kilkudziesięciu minut do kilku minut i pomaga użytkownikowi uniknąć kontaktu ze słabymi lub ryzykownymi ofertami.

