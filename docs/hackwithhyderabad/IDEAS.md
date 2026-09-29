# HackwithHyderabad 3.0: idea options + tonight's plan

Deadline: **29 Sept 2026 (tonight)**. Must use **Hindsight** (retain / recall / reflect, memory "banks").
Judging: Innovation 30 · Hindsight memory 25 · Tech 20 · UX 15 · Impact 10.

---

## Option A (recommended): **FixLens Field**, a service technician's copilot that remembers every home and every fix

**Problem.** Appliance service companies (brand service centres, Urban Company-style platforms) send technicians
into homes blind. The tech doesn't know this machine was repaired twice before, that the customer's water is hard,
or that on this model "E4" is almost always the inlet filter, not the valve. So they come back a second time,
order the wrong part, or ask the customer to repeat everything.

**Agent.** Before a job, the tech types or says "Mrs. Rao, Koramangala, washing machine E4". The agent briefs them:
- **Home memory** (bank `home-<customer>`): past visits, parts replaced, the customer's notes ("pump replaced in
  March, still leaking under the door").
- **Team memory** (bank `team-knowledge`): what fixes actually worked across *all* jobs ("E4 on front-loaders in
  hard-water areas: 7 of 9 were the inlet filter mesh").
- After the job, the tech's closing note ("cleaned inlet filter, fixed") is **retained**, so the next job is smarter.

**Why memory is the star (before / after demo).**
- Job 1, empty memory: generic checklist from the KB ("check tap, hose, filter, valve…").
- Job 10: "Last time on this machine the drain pump was replaced. E4 in this area is usually the filter.
  Bring a filter mesh, 80% first-visit fix."
- A toggle on screen, **memory off / memory on**, shows the same question side by side.

**Why it wins.** Innovation: physical-world repair, not another sales or support chatbot. Impact: first-visit fix
rate is money for service companies (fewer repeat visits). Reuses our 60-entry generic KB and the FixLens story
("the offline assistant for the homeowner, the memory agent for the pros").

## Option B: **Fleet maintenance memory** (cab and logistics fleets)

One bank per vehicle plus a fleet bank. Drivers report symptoms ("pulling left, squeal on braking"). The agent
recalls that vehicle's history and learns patterns across the fleet ("the 2021 Dzire batch eats brake pads at
~18k km"), then suggests what to check before the vehicle goes out. Reuses the car KB. Good business case, but the
demo data (many vehicles over months) takes longer to make realistic.

## Option C (safe but crowded): **Incident response agent**

On their list of example ideas, so many teams will build it, which hurts the 30% for innovation. Only pick it if
A feels too big.

---

## Tonight's build plan for Option A (about 6–8 h)

**Stack (the fastest path):** Python + FastAPI, a simple single-page web UI (or Streamlit), **Hindsight Cloud**
(promo `MEMHACK99`; no Docker time) or `pip install hindsight-all` embedded, **Groq** LLM (`openai/gpt-oss-120b`).
A new, separate GitHub repo (clean README for the judges).

| Time | Step |
|---|---|
| 0:00–0:30 | Fill in the **Profile Review Form**. Create the repo, sign up for Hindsight Cloud + Groq, run the hello-world retain/recall. |
| 0:30–1:30 | **Synthetic data:** 8 homes, ~40 past job reports (realistic names, areas, models, error codes, parts, outcomes; some fixes failed). Script `seed.py` retains them into `home-*` and `team-knowledge`. |
| 1:30–3:30 | **Agent:** `brief(customer, appliance, code)` = recall from the home bank + the team bank + KB entry → Groq writes a 5-line brief (likely cause, parts to bring, warnings, what failed before). `close_job(note)` = retain into both banks. Optional `reflect` on the team bank for "what have we learned about E4?" |
| 3:30–4:30 | **UI:** job form → brief card, "memory on/off" toggle, a "memories used" panel (shows the recalled facts, so the judges *see* memory), close-job box. |
| 4:30–5:00 | README: problem, architecture diagram (mermaid), how Hindsight is used (banks, retain/recall/reflect), run steps. |
| 5:00–6:00 | **Video** (2–5 min, 1080p screen + voice, YouTube public): problem → memory-off brief → close 3 jobs live → memory-on brief gets sharper. |
| 6:00–7:30 | **Article** (800–1,500 words, Dev.to/Medium, public) + **Reddit** link post (r/aiagents) + **LinkedIn post** (repo link in the post, Hindsight GitHub in the first comment). Use the guide's prompts in this repo. **Never write the word "hackathon".** |
| 7:30 | Submit the form: https://forms.gle/cD7fCnPnkdVm2sH78 |

**Cut if short on time:** voice input, reflect, a pretty UI. **Never cut:** the memory on/off before/after moment
and the "memories used" panel.

## Demo story (60 seconds)

"Ravi fixes washing machines. Monday, job 1: the agent knows nothing and gives him a generic checklist; he visits
twice. Friday, job 12: same error code, different home. The agent says: 'E4 here is usually the inlet filter, and
this customer's pump was replaced in March, so check the door seal too. Bring a filter mesh.' Fixed in one visit.
Nobody trained it. It remembered."
