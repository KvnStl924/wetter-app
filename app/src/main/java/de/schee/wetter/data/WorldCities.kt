package de.schee.wetter.data

/**
 * Großstädte weltweit für die Werte auf der Radarkarte (Temperatur, Wind), wenn die Karte weit
 * herausgezoomt ist und selbst kaum Städte beschriftet. Rang 1 = sehr wichtig (zuerst gezeigt).
 * Beim Hineinzoomen kommen die Orte dazu, die die Karte selbst beschriftet (auch kleine Städte).
 */
object WorldCities {
    data class City(val name: String, val lat: Double, val lon: Double, val rank: Int)

    val ALL: List<City> = """
        Berlin;52.52;13.40;1|London;51.51;-0.13;1|Paris;48.86;2.35;1|Madrid;40.42;-3.70;1|Rom;41.90;12.50;1|Moskau;55.76;37.62;1
        Istanbul;41.01;28.98;1|Kairo;30.04;31.24;1|Lagos;6.52;3.38;1|Kinshasa;-4.32;15.31;1|Johannesburg;-26.20;28.05;1|Nairobi;-1.29;36.82;1
        New York;40.71;-74.01;1|Los Angeles;34.05;-118.24;1|Chicago;41.88;-87.63;1|Mexiko-Stadt;19.43;-99.13;1|São Paulo;-23.55;-46.63;1|Buenos Aires;-34.60;-58.38;1
        Lima;-12.05;-77.04;1|Bogotá;4.71;-74.07;1|Peking;39.90;116.40;1|Shanghai;31.23;121.47;1|Tokio;35.68;139.69;1|Seoul;37.57;126.98;1
        Delhi;28.61;77.21;1|Mumbai;19.08;72.88;1|Dhaka;23.81;90.41;1|Karatschi;24.86;67.01;1|Bangkok;13.76;100.50;1|Jakarta;-6.21;106.85;1
        Manila;14.60;120.98;1|Sydney;-33.87;151.21;1|Teheran;35.69;51.39;1|Riad;24.71;46.68;1|Dubai;25.20;55.27;1|Toronto;43.65;-79.38;1
        Hamburg;53.55;9.99;2|München;48.14;11.58;2|Köln;50.94;6.96;2|Frankfurt;50.11;8.68;2|Wien;48.21;16.37;2|Zürich;47.38;8.54;2
        Warschau;52.23;21.01;2|Prag;50.08;14.44;2|Budapest;47.50;19.04;2|Bukarest;44.43;26.10;2|Kiew;50.45;30.52;2|Stockholm;59.33;18.07;2
        Oslo;59.91;10.75;2|Kopenhagen;55.68;12.57;2|Helsinki;60.17;24.94;2|Amsterdam;52.37;4.90;2|Brüssel;50.85;4.35;2|Lissabon;38.72;-9.14;2
        Barcelona;41.39;2.17;2|Mailand;45.46;9.19;2|Athen;37.98;23.73;2|Dublin;53.35;-6.26;2|Edinburgh;55.95;-3.19;2|Reykjavík;64.15;-21.94;2
        Sankt Petersburg;59.93;30.34;2|Minsk;53.90;27.57;2|Ankara;39.93;32.86;2|Tunis;36.81;10.18;2|Algier;36.75;3.06;2|Casablanca;33.57;-7.59;2
        Dakar;14.72;-17.47;2|Abidjan;5.36;-4.01;2|Accra;5.60;-0.19;2|Addis Abeba;9.03;38.74;2|Khartum;15.50;32.56;2|Daressalam;-6.79;39.21;2
        Luanda;-8.84;13.23;2|Kapstadt;-33.92;18.42;2|Antananarivo;-18.88;47.51;2|Tripolis;32.89;13.19;2|Bagdad;33.31;44.37;2|Tel Aviv;32.09;34.78;2
        Washington;38.91;-77.04;2|Miami;25.76;-80.19;2|Houston;29.76;-95.37;2|Dallas;32.78;-96.80;2|Denver;39.74;-104.99;2|Seattle;47.61;-122.33;2
        San Francisco;37.77;-122.42;2|Atlanta;33.75;-84.39;2|Montreal;45.50;-73.57;2|Vancouver;49.28;-123.12;2|Havanna;23.11;-82.37;2|Caracas;10.48;-66.90;2
        Santiago;-33.45;-70.67;2|Rio de Janeiro;-22.91;-43.17;2|Brasília;-15.79;-47.88;2|Manaus;-3.12;-60.02;2|Quito;-0.18;-78.47;2|La Paz;-16.50;-68.15;2
        Hongkong;22.32;114.17;2|Singapur;1.35;103.82;2|Kuala Lumpur;3.14;101.69;2|Ho-Chi-Minh-Stadt;10.82;106.63;2|Hanoi;21.03;105.85;2|Taipeh;25.03;121.57;2
        Osaka;34.69;135.50;2|Chengdu;30.57;104.07;2|Kanton;23.13;113.26;2|Wuhan;30.59;114.31;2|Kolkata;22.57;88.36;2|Chennai;13.08;80.27;2
        Bangalore;12.97;77.59;2|Kabul;34.56;69.21;2|Taschkent;41.30;69.24;2|Almaty;43.24;76.89;2|Melbourne;-37.81;144.96;2|Perth;-31.95;115.86;2
        Brisbane;-27.47;153.03;2|Auckland;-36.85;174.76;2|Honolulu;21.31;-157.86;2|Anchorage;61.22;-149.90;2|Nowosibirsk;55.01;82.94;2|Jekaterinburg;56.84;60.60;2
        Stuttgart;48.78;9.18;3|Düsseldorf;51.23;6.78;3|Leipzig;51.34;12.37;3|Dresden;51.05;13.74;3|Hannover;52.38;9.73;3|Nürnberg;49.45;11.08;3
        Bremen;53.08;8.80;3|Lyon;45.76;4.84;3|Marseille;43.30;5.37;3|Bordeaux;44.84;-0.58;3|Neapel;40.85;14.27;3|Sevilla;37.39;-5.98;3
        Valencia;39.47;-0.38;3|Porto;41.15;-8.61;3|Manchester;53.48;-2.24;3|Glasgow;55.86;-4.25;3|Belgrad;44.79;20.45;3|Sofia;42.70;23.32;3
        Zagreb;45.81;15.98;3|Riga;56.95;24.11;3|Vilnius;54.69;25.28;3|Tallinn;59.44;24.75;3|Bergen;60.39;5.32;3|Tromsø;69.65;18.96;3
        Palma;39.57;2.65;3|Las Palmas;28.12;-15.43;3|Marrakesch;31.63;-8.01;3|Bamako;12.64;-8.00;3|Niamey;13.51;2.13;3|N'Djamena;12.13;15.06;3
        Timbuktu;16.77;-3.01;3|Tamanrasset;22.79;5.53;3|Kufra;24.18;23.31;3|Windhoek;-22.56;17.08;3|Lusaka;-15.39;28.32;3|Harare;-17.83;31.05;3
        Maputo;-25.97;32.57;3|Kampala;0.35;32.58;3|Mogadischu;2.05;45.32;3|Dschidda;21.49;39.19;3|Maskat;23.59;58.41;3|Baku;40.41;49.87;3
        Phoenix;33.45;-112.07;3|Las Vegas;36.17;-115.14;3|Minneapolis;44.98;-93.27;3|Boston;42.36;-71.06;3|New Orleans;29.95;-90.07;3|Calgary;51.05;-114.07;3
        Winnipeg;49.90;-97.14;3|Québec;46.81;-71.21;3|Guatemala-Stadt;14.63;-90.51;3|Panama-Stadt;8.98;-79.52;3|Santo Domingo;18.49;-69.93;3|Montevideo;-34.90;-56.16;3
        Asunción;-25.26;-57.58;3|Córdoba;-31.42;-64.18;3|Recife;-8.05;-34.88;3|Fortaleza;-3.73;-38.53;3|Belém;-1.46;-48.50;3|Ushuaia;-54.80;-68.30;3
        Irkutsk;52.29;104.30;3|Jakutsk;62.03;129.73;3|Wladiwostok;43.12;131.89;3|Ulaanbaatar;47.89;106.91;3|Lhasa;29.65;91.17;3|Urumqi;43.83;87.62;3
        Bischkek;42.87;74.59;3|Colombo;6.93;79.86;3|Rangun;16.84;96.17;3|Denpasar;-8.65;115.22;3|Port Moresby;-9.44;147.18;3|Darwin;-12.46;130.84;3
        Alice Springs;-23.70;133.88;3|Adelaide;-34.93;138.60;3|Hobart;-42.88;147.33;3|Wellington;-41.29;174.78;3|Suva;-18.14;178.44;3|Nuuk;64.18;-51.72;3
        Murmansk;68.97;33.08;3|Norilsk;69.35;88.20;3|Fairbanks;64.84;-147.72;3|Yellowknife;62.45;-114.37;3|Iqaluit;63.75;-68.52;3|Longyearbyen;78.22;15.65;3
    """.trimIndent().split('|', '\n').mapNotNull { s ->
        val p = s.trim().split(';'); if (p.size < 4) null else City(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toInt())
    }.distinctBy { it.name }
}
