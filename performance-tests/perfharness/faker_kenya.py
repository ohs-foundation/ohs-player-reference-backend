"""Kenyan-flavored realistic data generation, styled after gen/test-orgs.csv.

Wraps a plain Faker instance (Faker has no strong en_KE locale worth relying
on) with hand-authored pools of Kenyan counties/cities, facility naming
templates, and phone/address formats matching the existing sample data in
gen/test-orgs.csv (e.g. "Kenyatta National Hospital", "+254202726300").
"""

import random

from faker import Faker

fake = Faker("en_US")

KENYAN_COUNTIES = [
    "Nairobi",
    "Mombasa",
    "Kisumu",
    "Nakuru",
    "Eldoret",
    "Nyeri",
    "Kisii",
    "Embu",
    "Thika",
    "Bomet",
    "Kijabe",
    "Machakos",
    "Meru",
    "Kakamega",
    "Garissa",
    "Kericho",
    "Kitale",
    "Malindi",
    "Nyahururu",
    "Naivasha",
]

HOSPITAL_SUFFIXES = [
    "County Hospital",
    "Level 5 Hospital",
    "District Hospital",
    "Referral Hospital",
    "Medical Centre",
    "Sub-County Hospital",
]

DEPARTMENT_NAMES = [
    "Cardiology",
    "Oncology",
    "Paediatrics",
    "Radiology",
    "Maternity",
    "Surgery",
    "Outpatient",
    "Accident and Emergency",
    "Laboratory",
    "Pharmacy",
    "Physiotherapy",
    "Dental",
]

STREETS = [
    "Moi Avenue",
    "Kenyatta Avenue",
    "Uhuru Highway",
    "Ngong Road",
    "Waiyaki Way",
    "Mombasa Road",
    "Jomo Kenyatta Highway",
    "Nyerere Road",
    "Hospital Road",
    "Kimathi Street",
]

MOBILE_PREFIXES = ["70", "71", "72", "74", "79"]
LANDLINE_PREFIXES = ["20", "41", "51", "53", "57"]


def kenyan_county() -> str:
    return random.choice(KENYAN_COUNTIES)


def hospital_name(county: str) -> str:
    return f"{county} {random.choice(HOSPITAL_SUFFIXES)}"


def department_name() -> str:
    return random.choice(DEPARTMENT_NAMES)


def team_name(department: str) -> str:
    return f"{department} Team {random.randint(1, 9)}"


def kenyan_mobile_phone() -> str:
    prefix = random.choice(MOBILE_PREFIXES)
    return f"+254{prefix}{random.randint(1000000, 9999999)}"


def kenyan_landline_phone() -> str:
    prefix = random.choice(LANDLINE_PREFIXES)
    return f"+254{prefix}{random.randint(1000000, 9999999)}"


def kenyan_physical_address(city: str) -> str:
    street = random.choice(STREETS)
    return f"{street} {city}"


def kenyan_postal_address(city: str) -> str:
    return f"P.O. Box {random.randint(100, 90000)}-{random.randint(100, 99999)} {city}"


def practitioner_full_name() -> tuple[str, str]:
    """Global-scale names via Faker, not a small hand-rolled pool -- at 5k-10k
    rows a curated Kenyan name list would repeat heavily and look fake."""
    return fake.first_name(), fake.last_name()


def kenyan_username(first_name: str, suffix: int) -> str:
    return f"{first_name.lower()}{suffix}"


def kenyan_email(username: str, domain: str = "ohs.dev") -> str:
    return f"{username}@{domain}"


def kenyan_national_id() -> str:
    return f"NID-{random.randint(10000000, 39999999)}"


def kenyan_dob(min_age: int = 22, max_age: int = 65) -> str:
    return fake.date_of_birth(minimum_age=min_age, maximum_age=max_age).isoformat()


def gender() -> str:
    return random.choice(["male", "female"])


def jittered_coordinate(base: float, spread: float = 0.05) -> float:
    return round(base + random.uniform(-spread, spread), 6)


# Approximate coordinates for the cities in KENYAN_COUNTIES, used to cluster
# generated locations geographically rather than scattering them randomly.
CITY_COORDINATES = {
    "Nairobi": (-1.286389, 36.817223),
    "Mombasa": (-4.043477, 39.658871),
    "Kisumu": (-0.091702, 34.767956),
    "Nakuru": (-0.303099, 36.080025),
    "Eldoret": (0.520360, 35.269779),
    "Nyeri": (-0.420130, 36.947685),
    "Kisii": (-0.680562, 34.766701),
    "Embu": (-0.539435, 37.457516),
    "Thika": (-1.033260, 37.069328),
    "Bomet": (-0.782123, 35.341328),
    "Kijabe": (-0.933333, 36.583333),
    "Machakos": (-1.516667, 37.266667),
    "Meru": (0.047280, 37.649803),
    "Kakamega": (0.282730, 34.751812),
    "Garissa": (-0.453611, 39.646099),
    "Kericho": (-0.368580, 35.283329),
    "Kitale": (1.015270, 35.006180),
    "Malindi": (-3.219500, 40.116600),
    "Nyahururu": (0.036780, 36.363960),
    "Naivasha": (-0.716670, 36.433330),
}


def base_coordinate(city: str) -> tuple[float, float]:
    return CITY_COORDINATES.get(city, CITY_COORDINATES["Nairobi"])
