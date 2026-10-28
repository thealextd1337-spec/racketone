-- Konten weltweit: einheitlich 16 außerhalb der Bestandsregeln, Embargoländer und Fantasiecodes bleiben gesperrt.
begin;
select plan(9);

select is((select count(*)::int from public.country_age_rules where allowed), 245, '245 freigeschaltete Länder');
select is((select min_age from public.country_age_rules where country = 'AT'), 14::smallint, 'AT bleibt 14');
select is((select legal_note from public.country_age_rules where country = 'DE'), 'Art. 8 Abs. 1 DSGVO', 'DE-Regel unverändert');
select is(private.age_result('US','2010-10-28','2026-10-28 12:00+00'), 'ok', 'US: genau 16');
select is(private.age_result('US','2010-10-29','2026-10-28 12:00+00'), 'age_below_minimum', 'US: Tag vor dem 16. Geburtstag');
select is(private.age_result('IN','1990-01-01',now()), 'ok', 'IN freigeschaltet');
select is(private.age_result('KP','1990-01-01',now()), 'country_not_supported', 'Embargoland KP gesperrt');
select is(private.age_result('IR','1990-01-01',now()), 'country_not_supported', 'Embargoland IR gesperrt');
select is(private.age_result('XX','1990-01-01',now()), 'country_not_supported', 'Kein ISO-Land');

select * from finish();
rollback;
