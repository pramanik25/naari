from pathlib import Path
W,H=960,540
OUT=Path(__file__).resolve().parents[1]/'Naari_Kavach_Features.pdf'
pages=[]
def esc(s): return s.replace('\\','\\\\').replace('(','\\(').replace(')','\\)')
def tx(x,y,s,z=14,col=(.15,.18,.23),f='F1'):
 r,g,b=col; return f'BT /{f} {z} Tf {r} {g} {b} rg 1 0 0 1 {x} {y} Tm ({esc(s)}) Tj ET\n'
def box(x,y,w,h,col):
 r,g,b=col; return f'{r} {g} {b} rg {x} {y} {w} {h} re f\n'
def lines(s,n):
 out=[]; cur=''
 for word in s.split():
  if cur and len(cur)+len(word)+1>n: out.append(cur); cur=word
  else: cur=(cur+' '+word).strip()
 if cur: out.append(cur)
 return out
def base(title,section,num):
 s=box(0,0,W,H,(.975,.965,.95))+box(0,0,12,H,(.68,.12,.32))
 s+=tx(52,494,section.upper(),11,(.68,.12,.32),'F2')+tx(52,453,title,31,(.13,.16,.22),'F2')
 s+=box(52,34,856,1,(.86,.83,.81))+tx(52,16,'NAARI KAVACH  |  FEATURE OVERVIEW',9,(.4,.42,.45))+tx(884,16,f'{num:02d}',9,(.4,.42,.45))
 return s
def card(s,x,y,w,h,title,body,accent=(.68,.12,.32),chars=42):
 s+=box(x,y,w,h,(1,1,1))+box(x,y+h-6,w,6,accent)+tx(x+18,y+h-34,title,16,(.15,.18,.23),'F2'); yy=y+h-59
 for p in body:
  for ln in lines(p,chars): s+=tx(x+18,yy,ln,12,(.31,.34,.38)); yy-=17
  yy-=5
 return s
# Cover
s=box(0,0,W,H,(.13,.11,.18))+box(0,0,14,H,(.83,.20,.40))+box(625,0,335,H,(.23,.17,.27))
s+=tx(64,458,'NAARI KAVACH',15,(.98,.63,.70),'F2')+tx(64,365,'Safety tools for',40,(1,1,1),'F2')+tx(64,313,'everyday confidence',40,(1,1,1),'F2')
s+=tx(66,253,'A feature overview of the Naari Kavach app',18,(.88,.86,.89))+tx(66,205,'Emergency support, safer journeys, trusted circles,',15,(.88,.86,.89))+tx(66,181,'evidence tools and daily wellbeing in one place.',15,(.88,.86,.89))
s+=box(668,143,230,254,(.68,.12,.32))+tx(715,350,'NAARI',16,(1,1,1),'F2')+tx(703,322,'KAVACH',24,(1,1,1),'F2')+box(706,270,154,2,(1,.65,.72))+tx(700,239,'SAFETY  -  SUPPORT',12,(1,.9,.92),'F2')+tx(684,217,'AT YOUR FINGERTIPS',12,(1,.9,.92),'F2')
s+=tx(66,34,'Explore app features and download from the Naari Kavach website',10,(.72,.70,.75)); pages.append(s)
# page 2
s=base('Emergency help, quickly accessible','01  /  Emergency response',2)
s=card(s,52,248,270,162,'One-touch SOS',['A press-and-hold SOS action starts the emergency response flow.','The protection screen shows whether safety protection is active.'],chars=36)
s=card(s,345,248,270,162,'Trusted contacts',['Add and manage emergency contacts for alerts.','SOS can share incident details and location with configured contacts.'],(.82,.43,.23),36)
s=card(s,638,248,270,162,'Calls and helplines',['Quick access to police calling and womens helpline information.','Numbers and availability depend on region and device.'],(.28,.48,.61),36)
s+=box(52,91,856,110,(.94,.90,.91))+tx(74,168,'Live location and alert delivery',17,(.15,.18,.23),'F2')+tx(74,140,'Incident location can be shared with contacts and, where enabled, nearby opted-in helpers.',13)+tx(74,119,'Push, SMS, WhatsApp and network delivery depend on permissions, connectivity and service configuration.',11,(.42,.42,.44)); pages.append(s)
# page 3
s=base('Multiple ways to activate protection','02  /  SOS triggers',3)+tx(52,409,'Optional hands-free and discreet triggers can be configured in the app.',14,(.31,.34,.38))
items=[('Voice phrase','Offline voice trigger phrase can start SOS.'),('Volume keys','A configured key sequence can trigger SOS when enabled.'),('Scream detection','On-device audio classification can recognize distress sounds.'),('Fall detection','Motion sensor patterns can trigger an SOS flow.'),('Headset / BLE button','Supported headset actions or paired BLE button can act as a trigger.'),('Device guard','SIM or shutdown signals can invoke configured safeguards.')]
for i,(t,b) in enumerate(items): s=card(s,52+(i%3)*293,267-(i//3)*159,270,134,t,[b],[(.68,.12,.32),(.82,.43,.23),(.28,.48,.61)][i%3],36)
s+=tx(54,75,'Triggers require setup and Android permissions; sensor-based detection may be imperfect.',11,(.42,.42,.44)); pages.append(s)
# page 4
s=base('Safer journeys and location sharing','03  /  Journey and location',4)
s=card(s,52,251,400,160,'Timed safety check-ins',['Set a check-in deadline and optional note.','Overdue check-ins can escalate into an incident and notify configured contacts.'],chars=59)
s=card(s,476,251,432,160,'Journey tools',['Cab and meeting safety modes, trip details and safety settings support planning while travelling.','A live journey entry can appear on the home screen.'],(.82,.43,.23),63)
s=card(s,52,79,400,145,'Safe places and geofences',['Manage saved safe zones and geofence settings.','Permissions and background access affect operation.'],(.28,.48,.61),59)
s=card(s,476,79,432,145,'Live sharing and safety map',['Share live location with a circle and view community safety information on a map.','Sharing is controlled by the user and location access.'],(.41,.48,.31),63); pages.append(s)
# page 5
s=base('Capture, organize and document','04  /  Evidence and records',5)
s=card(s,52,251,400,160,'Emergency evidence capture',['Emergency flows can collect photos, audio and video where supported.','Uploads can be associated with an incident.'],chars=59)
s=card(s,476,251,432,160,'Evidence vault and export',['Review stored emergency media and incident records.','Tools include export and report or complaint templates.'],(.28,.48,.61),63)
s=card(s,52,79,400,145,'Private incident diary',['Record incident details in a diary and keep a personal timeline.'],(.82,.43,.23),59)
s=card(s,476,79,432,145,'Cloud delivery',['Uploads use the companion API when configured; retry workers help handle connectivity interruptions.'],(.41,.48,.31),63); pages.append(s)
# page 6
s=base('Support from trusted people and community','05  /  Together',6)
s=card(s,52,248,270,162,'Trusted Circle',['Link guardians or trusted people.','Share location when the user enables sharing.'],chars=36)
s=card(s,345,248,270,162,'Nearby helpers',['Opt-in volunteers can receive nearby SOS alerts and respond through the app.'],(.82,.43,.23),36)
s=card(s,638,248,270,162,'Community and map',['Community posts, discussion threads and safety map features connect local users.'],(.28,.48,.61),36)
s+=box(52,100,856,100,(.94,.90,.91))+tx(74,166,'Controls built into the experience',17,(.15,.18,.23),'F2')+tx(74,139,'Helper participation is opt-in. Community content and helper activity depend on active users and network access.',12)+tx(74,117,'Alerts and location visibility depend on the selected settings and backend services.',12); pages.append(s)
# page 7
s=base('Everyday wellbeing and practical safety','06  /  Daily life',7)
s=card(s,52,249,270,162,'Cycle tracking',['Log cycle information and view estimates in the daily section.'],chars=36)
s=card(s,345,249,270,162,'Quizzes and learning',['Daily quiz and safety education content encourage practical awareness.'],(.82,.43,.23),36)
s=card(s,638,249,270,162,'Commute tools',['Commute planning and daily prompts bring safety habits into routines.'],(.28,.48,.61),36)
s=card(s,52,79,400,145,'Discreet support',['A fake call feature can provide a planned interruption or exit aid.'],(.41,.48,.31),59)
s=card(s,476,79,432,145,'Personal settings',['Profile, appearance, PIN, trigger setup, emergency contacts and safety preferences are managed in the app.'],(.68,.12,.32),63); pages.append(s)
# Final slide: website link for more feature details and app download.
s=base('Explore features and download the app','07  /  Get Naari Kavach',8)
s+=tx(74,374,'Read about the features and get the app from our website.',19,(.25,.28,.32))
s+=box(52,182,856,142,(1,1,1))+box(52,318,856,6,(.68,.12,.32))
s+=tx(78,278,'NAARI KAVACH WEBSITE',12,(.68,.12,.32),'F2')
s+=tx(78,235,'https://naari-theta.vercel.app/#download',19,(.13,.16,.22),'F2')
s+=tx(78,204,'Tap the link in this slide deck to read more and download.',12,(.38,.40,.43))
s+=tx(52,113,'Some app features require setup, permissions, network access or configured services.',12,(.38,.40,.43))
s+=tx(52,90,'The app does not replace local emergency services.',12,(.38,.40,.43)); pages.append(s)

objs=[]
def obj(data): objs.append(data if isinstance(data,bytes) else data.encode('latin-1')); return len(objs)
f1=obj('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'); f2=obj('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>'); streams=[]
for p in pages: streams.append(obj(f'<< /Length {len(p.encode("latin-1"))} >>\nstream\n{p}endstream'))
pid=obj(''); kids=[]
link=obj('<< /Type /Annot /Subtype /Link /Rect [74 221 832 265] /Border [0 0 1] /C [0.68 0.12 0.32] /A << /S /URI /URI (https://naari-theta.vercel.app/#download) >> >>')
for i,cid in enumerate(streams):
 annots=f' /Annots [{link} 0 R]' if i==len(streams)-1 else ''
 kids.append(obj(f'<< /Type /Page /Parent {pid} 0 R /MediaBox [0 0 {W} {H}] /Resources << /Font << /F1 {f1} 0 R /F2 {f2} 0 R >> >> /Contents {cid} 0 R{annots} >>'))
objs[pid-1]=f'<< /Type /Pages /Kids [{" ".join(str(k)+" 0 R" for k in kids)}] /Count {len(kids)} >>'.encode()
root=obj(f'<< /Type /Catalog /Pages {pid} 0 R >>'); out=bytearray(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n'); offsets=[0]
for i,o in enumerate(objs,1): offsets.append(len(out)); out.extend(f'{i} 0 obj\n'.encode()); out.extend(o); out.extend(b'\nendobj\n')
xref=len(out); out.extend(f'xref\n0 {len(objs)+1}\n0000000000 65535 f \n'.encode())
for off in offsets[1:]: out.extend(f'{off:010d} 00000 n \n'.encode())
out.extend(f'trailer\n<< /Size {len(objs)+1} /Root {root} 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()); OUT.write_bytes(out); print(OUT)
