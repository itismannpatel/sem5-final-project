// src/data/catalog.ts
var CATEGORIES = [
  { id: "Programming", label: "Programming", glyph: "{ }" },
  { id: "Design", label: "Design", glyph: "\u25D1" },
  { id: "Music", label: "Music", glyph: "\u266A" },
  { id: "Languages", label: "Languages", glyph: "\u6587" },
  { id: "Academics", label: "Academics", glyph: "\u2211" },
  { id: "Fitness", label: "Fitness", glyph: "\u25CE" },
  { id: "Business", label: "Business", glyph: "\u25B2" },
  { id: "Arts", label: "Arts", glyph: "\u2726" }
];
var CATEGORY_BY_ID = Object.fromEntries(
  CATEGORIES.map((c) => [c.id, c])
);
var SKILLS = [
  // Programming
  { id: "sk_python", name: "Python", category: "Programming" },
  { id: "sk_js", name: "JavaScript", category: "Programming" },
  { id: "sk_react", name: "React", category: "Programming" },
  { id: "sk_rust", name: "Rust", category: "Programming" },
  { id: "sk_cpp", name: "C++", category: "Programming" },
  { id: "sk_ml", name: "Machine Learning", category: "Programming" },
  { id: "sk_sql", name: "SQL", category: "Programming" },
  { id: "sk_dsa", name: "Data Structures", category: "Programming" },
  // Design
  { id: "sk_figma", name: "Figma", category: "Design" },
  { id: "sk_uiux", name: "UI/UX Design", category: "Design" },
  { id: "sk_motion", name: "Motion Design", category: "Design" },
  { id: "sk_illustration", name: "Illustration", category: "Design" },
  { id: "sk_blender", name: "Blender 3D", category: "Design" },
  // Music
  { id: "sk_guitar", name: "Guitar", category: "Music" },
  { id: "sk_piano", name: "Piano", category: "Music" },
  { id: "sk_production", name: "Music Production", category: "Music" },
  { id: "sk_singing", name: "Singing", category: "Music" },
  { id: "sk_tabla", name: "Tabla", category: "Music" },
  // Languages
  { id: "sk_spanish", name: "Spanish", category: "Languages" },
  { id: "sk_japanese", name: "Japanese", category: "Languages" },
  { id: "sk_german", name: "German", category: "Languages" },
  { id: "sk_french", name: "French", category: "Languages" },
  { id: "sk_speaking", name: "Public Speaking", category: "Languages" },
  // Academics
  { id: "sk_calculus", name: "Calculus", category: "Academics" },
  { id: "sk_linalg", name: "Linear Algebra", category: "Academics" },
  { id: "sk_physics", name: "Physics", category: "Academics" },
  { id: "sk_chem", name: "Organic Chemistry", category: "Academics" },
  { id: "sk_writing", name: "Technical Writing", category: "Academics" },
  // Fitness
  { id: "sk_calisthenics", name: "Calisthenics", category: "Fitness" },
  { id: "sk_yoga", name: "Yoga", category: "Fitness" },
  { id: "sk_running", name: "Running", category: "Fitness" },
  { id: "sk_swimming", name: "Swimming", category: "Fitness" },
  // Business
  { id: "sk_pm", name: "Product Management", category: "Business" },
  { id: "sk_marketing", name: "Marketing", category: "Business" },
  { id: "sk_finance", name: "Finance", category: "Business" },
  { id: "sk_pitching", name: "Pitching", category: "Business" },
  { id: "sk_excel", name: "Excel Modelling", category: "Business" },
  // Arts
  { id: "sk_photo", name: "Photography", category: "Arts" },
  { id: "sk_film", name: "Film Editing", category: "Arts" },
  { id: "sk_ceramics", name: "Ceramics", category: "Arts" },
  { id: "sk_sketching", name: "Sketching", category: "Arts" }
];
var SKILL_BY_ID = Object.fromEntries(
  SKILLS.map((s) => [s.id, s])
);

// src/data/store.ts
var EMPTY = {
  version: 0,
  users: [],
  skills: [],
  sessions: [],
  reviews: [],
  messages: [],
  payments: []
};
var snapshot = EMPTY;
function getSnapshot() {
  return snapshot;
}

// src/data/api.ts
function ratingFor(userId, db = getSnapshot()) {
  const reviews = db.reviews.filter((r) => r.toUserId === userId);
  if (reviews.length === 0) return { average: 0, count: 0 };
  const total = reviews.reduce((sum, r) => sum + r.rating, 0);
  return { average: Math.round(total / reviews.length * 10) / 10, count: reviews.length };
}

// src/lib/matching.ts
var MATCH_WEIGHTS = { learn: 45, teach: 35, affinity: 12, trust: 8 };
var skillName = (id) => SKILL_BY_ID[id]?.name ?? id;
var skillCategory = (id) => SKILL_BY_ID[id]?.category;
function names(skills) {
  const list = skills.map((s) => skillName(s.skillId));
  if (list.length <= 1) return list.join("");
  return `${list.slice(0, -1).join(", ")} and ${list[list.length - 1]}`;
}
function categorySet(user) {
  const set = /* @__PURE__ */ new Set();
  for (const skill of [...user.teaches, ...user.wants]) {
    const category = skillCategory(skill.skillId);
    if (category) set.add(category);
  }
  return set;
}
function scoreMatch(me, them, db) {
  const myWants = new Set(me.wants.map((s) => s.skillId));
  const theirWants = new Set(them.wants.map((s) => s.skillId));
  const canLearn = them.teaches.filter((s) => myWants.has(s.skillId));
  const canTeach = me.teaches.filter((s) => theirWants.has(s.skillId));
  const learn = Math.min(1, canLearn.length / 2) * MATCH_WEIGHTS.learn;
  const teach = Math.min(1, canTeach.length / 2) * MATCH_WEIGHTS.teach;
  const mine = categorySet(me);
  const theirs = categorySet(them);
  let shared = 0;
  mine.forEach((category) => {
    if (theirs.has(category)) shared += 1;
  });
  const affinity = Math.min(1, shared / 3) * MATCH_WEIGHTS.affinity;
  const rating = ratingFor(them.id, db);
  const trustValue = rating.count ? rating.average / 5 : 0.6;
  const trust = trustValue * MATCH_WEIGHTS.trust;
  const breakdown = {
    learn: Math.round(learn * 10) / 10,
    teach: Math.round(teach * 10) / 10,
    affinity: Math.round(affinity * 10) / 10,
    trust: Math.round(trust * 10) / 10
  };
  const score = Math.round(learn + teach + affinity + trust);
  const reciprocal = canLearn.length > 0 && canTeach.length > 0;
  const reasons = [];
  if (canLearn.length) reasons.push(`Teaches ${names(canLearn)} \u2014 on your wishlist`);
  if (canTeach.length) reasons.push(`Wants to learn ${names(canTeach)} \u2014 you teach it`);
  if (reciprocal) reasons.push("Two-way swap: you both gain something");
  else if (canLearn.length && !canTeach.length) reasons.push("One-way: they have what you want");
  if (rating.count >= 3 && rating.average >= 4.5) reasons.push(`Highly rated \u2014 ${rating.average.toFixed(1)} from ${rating.count} sessions`);
  if (!reasons.length) reasons.push("Shared subject areas \u2014 worth a conversation");
  return { user: them, score, breakdown, canLearn, canTeach, reciprocal, reasons };
}
function computeMatches(me, db) {
  return db.users.filter((u) => u.id !== me.id).map((u) => scoreMatch(me, u, db)).filter((m) => m.canLearn.length > 0).sort((a, b) => b.score - a.score || b.user.name.localeCompare(a.user.name));
}
function scoreEveryone(me, db) {
  return db.users.filter((u) => u.id !== me.id).map((u) => scoreMatch(me, u, db)).sort((a, b) => b.score - a.score);
}
export {
  MATCH_WEIGHTS,
  computeMatches,
  scoreEveryone,
  scoreMatch
};
