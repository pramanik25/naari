from pathlib import Path
import html

ROOT = Path(__file__).resolve().parents[1]
HTML_PATH = ROOT / "Naari_Kavach_Features_Hindi.html"

slides = [
    ("NAARI KAVACH", "सुरक्षा और आत्मविश्वास के लिए एक ऐप", [
        "आपातकालीन सहायता, सुरक्षित यात्रा, भरोसेमंद लोगों से जुड़ाव,",
        "घटना से जुड़े रिकॉर्ड और रोज़मर्रा की सेहत-सुरक्षा सुविधाएँ।",
    ], "परियोजना में उपलब्ध Android ऐप सुविधाओं का परिचय"),
    ("आपातकाल में मदद, आसानी से उपलब्ध", "01  /  आपातकालीन सहायता", [
        ("SOS बटन", ["SOS दबाकर आपातकालीन सहायता प्रक्रिया शुरू करें।", "ऐप में सुरक्षा सुविधा चालू है या नहीं, यह देखें।"]),
        ("भरोसेमंद संपर्क", ["अलर्ट पाने के लिए अपने आपातकालीन संपर्क जोड़ें।", "सेटिंग के अनुसार घटना की जानकारी और लोकेशन साझा हो सकती है।"]),
        ("कॉल और हेल्पलाइन", ["पुलिस को कॉल करने और महिला हेल्पलाइन की जानकारी तक पहुँचें।", "नंबर और उपलब्धता क्षेत्र व डिवाइस पर निर्भर करते हैं।"]),
    ], ("लाइव लोकेशन और अलर्ट", "घटना की लोकेशन संपर्कों और, सुविधा चालू होने पर, आसपास के सहमति देने वाले helpers के साथ साझा हो सकती है।", "पुश, SMS, WhatsApp और नेटवर्क से अलर्ट मिलना अनुमतियों, कनेक्टिविटी और सेवा सेटअप पर निर्भर है।")),
    ("सुरक्षा शुरू करने के कई तरीके", "02  /  SOS ट्रिगर", [
        ("वॉइस संकेत", "ऑफ़लाइन वॉइस ट्रिगर वाक्यांश से SOS शुरू हो सकता है।"),
        ("वॉल्यूम बटन", "सेटअप करने पर बटनों का तय क्रम SOS शुरू कर सकता है।"),
        ("चीख पहचान", "डिवाइस पर ऑडियो पहचान संकट की आवाज़ पहचान सकती है।"),
        ("गिरने की पहचान", "मोशन सेंसर के पैटर्न SOS प्रक्रिया शुरू कर सकते हैं।"),
        ("हेडसेट / BLE बटन", "सहायक हेडसेट या पेयर किया BLE बटन ट्रिगर बन सकता है।"),
        ("डिवाइस सुरक्षा", "SIM या फोन बंद होने से जुड़े संकेत सुरक्षा कार्रवाई शुरू कर सकते हैं।"),
    ], "इन ट्रिगर के लिए सेटअप और Android अनुमतियाँ ज़रूरी हैं। सेंसर की पहचान हमेशा सटीक नहीं हो सकती।"),
    ("सुरक्षित यात्रा और लोकेशन शेयरिंग", "03  /  यात्रा और लोकेशन", [
        ("समयबद्ध चेक-इन", ["चेक-इन का समय और वैकल्पिक संदेश तय करें।", "चेक-इन छूटने पर घटना दर्ज हो सकती है और चुने हुए संपर्कों को सूचना मिल सकती है।"]),
        ("यात्रा के टूल", ["कैब और मीटिंग सुरक्षा मोड, यात्रा की जानकारी और सुरक्षा सेटिंग इस्तेमाल करें।", "होम स्क्रीन पर चल रही यात्रा का सार दिख सकता है।"]),
        ("सुरक्षित जगहें", ["सुरक्षित स्थान और geofence सेटिंग मैनेज करें।", "लोकेशन और बैकग्राउंड अनुमतियाँ काम करने पर असर डालती हैं।"]),
        ("लाइव शेयरिंग और सुरक्षा नक्शा", ["अपने Circle के साथ लाइव लोकेशन साझा करें और समुदाय की सुरक्षा जानकारी देखें।", "लोकेशन शेयरिंग उपयोगकर्ता की सेटिंग और अनुमति पर निर्भर है।"]),
    ]),
    ("जानकारी और सबूत सुरक्षित रखें", "04  /  सबूत और रिकॉर्ड", [
        ("आपातकालीन सबूत", ["सहायता मिलने पर आपातकालीन प्रक्रिया फ़ोटो, ऑडियो और वीडियो दर्ज कर सकती है।", "अपलोड घटना के रिकॉर्ड से जुड़ सकते हैं।"]),
        ("सबूत संग्रह और निर्यात", ["सहेजे गए मीडिया और घटना के रिकॉर्ड देखें।", "सबूत निर्यात और शिकायत का प्रारूप बनाने के टूल उपलब्ध हैं।"]),
        ("निजी घटना डायरी", ["घटना का विवरण लिखें और अपना रिकॉर्ड सुरक्षित रखें।"]),
        ("क्लाउड अपलोड", ["सेवा सेटअप होने पर ऐप साथी API से अपलोड करता है; नेटवर्क लौटने पर दोबारा भेजने की सुविधा मदद करती है।"]),
    ]),
    ("भरोसेमंद लोगों और समुदाय का साथ", "05  /  साथ और समुदाय", [
        ("भरोसेमंद Circle", ["अभिभावक या भरोसेमंद लोगों को जोड़ें।", "आपकी अनुमति से लोकेशन साझा करें।"]),
        ("आसपास के helpers", ["सहमति देने वाले वॉलंटियर्स को आसपास के SOS अलर्ट मिल सकते हैं और वे ऐप से जवाब दे सकते हैं।"]),
        ("समुदाय और सुरक्षा नक्शा", ["कम्युनिटी पोस्ट, चर्चा और सुरक्षा नक्शे से स्थानीय लोगों से जुड़ें।"]),
    ], ("आपके नियंत्रण में", "Helper बनना स्वैच्छिक है। समुदाय और helpers की उपलब्धता सक्रिय उपयोगकर्ताओं व नेटवर्क पर निर्भर करती है।", "अलर्ट और लोकेशन किसे दिखेगी, यह सेटिंग और सेवा पर निर्भर है।")),
    ("रोज़मर्रा की सेहत और सुरक्षा", "06  /  हर दिन के लिए", [
        ("मासिक चक्र ट्रैकिंग", "जानकारी दर्ज करें और ऐप में दिए गए अनुमान देखें।"),
        ("क्विज़ और सीख", "रोज़ाना क्विज़ और सुरक्षा से जुड़ी जानकारी देखें।"),
        ("आवागमन के टूल", "आने-जाने की योजना और रोज़मर्रा के सुरक्षा सुझाव इस्तेमाल करें।"),
        ("डिस्क्रीट सहायता", "Fake call सुविधा योजनाबद्ध तरीके से बातचीत से निकलने में मदद कर सकती है।"),
        ("निजी सेटिंग", "प्रोफ़ाइल, ऐप का रूप, PIN, ट्रिगर, आपातकालीन संपर्क और सुरक्षा प्राथमिकताएँ मैनेज करें।"),
    ]),
    ("ऐप के बारे में जानें और डाउनलोड करें", "07  /  Naari Kavach पाएँ", [
        "वेबसाइट पर सुविधाओं के बारे में पढ़ें और ऐप डाउनलोड करें।",
        "https://naari-theta.vercel.app/#download",
        "कुछ सुविधाओं के लिए सेटअप, Android अनुमतियाँ, इंटरनेट या सेवा कॉन्फ़िगरेशन ज़रूरी हो सकते हैं।",
        "Naari Kavach स्थानीय आपातकालीन सेवाओं का विकल्प नहीं है।",
    ]),
]

def card(title, body, cls=""):
    if isinstance(body, str): body = [body]
    paras = "".join(f"<p>{html.escape(line)}</p>" for line in body)
    return f'<article class="card {cls}"><h2>{html.escape(title)}</h2>{paras}</article>'

parts = []
for i, item in enumerate(slides):
    title, kicker, content = item[:3]
    extra = item[3] if len(item) > 3 else None
    if i == 0:
        pieces = ''.join(f'<p>{html.escape(x)}</p>' for x in content)
        body = f'<div class="cover"><div class="brand">NAARI KAVACH</div><h1>{html.escape(title)}</h1><h2>{html.escape(kicker)}</h2><div class="intro">{pieces}</div><div class="cover-foot">{html.escape(extra)}</div></div>'
    elif i == 1:
        cards = ''.join(card(t,b) for t,b in content)
        body = f'<div class="three">{cards}</div><div class="note"><h2>{html.escape(extra[0])}</h2><p>{html.escape(extra[1])}</p><small>{html.escape(extra[2])}</small></div>'
    elif i == 2:
        cards = ''.join(card(t,b) for t,b in content)
        body = f'<div class="three">{cards}</div><div class="footnote">{html.escape(extra)}</div>'
    elif i == 3 or i == 4:
        body = '<div class="two">'+''.join(card(t,b) for t,b in content)+'</div>'
    elif i == 5:
        body = f'<div class="three">'+''.join(card(t,b) for t,b in content)+'</div><div class="note"><h2>{html.escape(extra[0])}</h2><p>{html.escape(extra[1])}</p><small>{html.escape(extra[2])}</small></div>'
    elif i == 6:
        body = '<div class="three">'+''.join(card(t,b) for t,b in content[:3])+'</div><div class="two lower">'+''.join(card(t,b) for t,b in content[3:])+'</div>'
    else:
        body = '<div class="web"><h2>वेबसाइट पर जाएँ</h2><a href="https://naari-theta.vercel.app/#download">https://naari-theta.vercel.app/#download</a></div><div class="closing">'+''.join(f'<p>{html.escape(x)}</p>' for x in content[0:1]+content[2:])+'</div>'
    parts.append(f'<section class="slide slide-{i}"><header><span>{html.escape(kicker)}</span><h1>{html.escape(title)}</h1></header>{body}<footer>NAARI KAVACH  |  सुविधाओं का परिचय <b>{i+1:02d}</b></footer></section>')

style = r'''
@page { size: 13.333in 7.5in; margin: 0; }
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; font-family: "Nirmala UI", "Noto Sans Devanagari", sans-serif; color: #242a38; }
.slide { width: 13.333in; height: 7.5in; position: relative; overflow: hidden; page-break-after: always; background: #f9f7f3; padding: .58in .72in .56in; }
.slide:last-child { page-break-after: auto; }
.slide:before { content: ""; position:absolute; left:0; top:0; bottom:0; width:.16in; background:#ad1f52; }
header span { color:#ad1f52; font-size:11pt; font-weight:700; letter-spacing:.5px; }
header h1 { font-size:26pt; line-height:1.15; margin:12px 0 0; }
footer { position:absolute; bottom:.18in; left:.72in; right:.72in; border-top:1px solid #ddd5d1; padding-top:8px; color:#777; font-size:8pt; }
footer b { float:right; font-weight:400; }
.cover { margin:-.58in -.72in; height:7.5in; padding:1.0in .95in; background:linear-gradient(110deg,#211c2e 0%,#211c2e 66%,#392a3a 66%,#392a3a 100%); color:white; position:relative; }
.cover:before { content:""; position:absolute; inset:0 auto 0 0; width:.17in; background:#d43b68; }
.brand { color:#f8aabc; font-size:15pt; font-weight:700; margin-bottom:.5in; }
.cover h1 { font-size:38pt; margin:0 0 10px; }.cover h2 { font-size:24pt; margin:0 0 30px; color:#f1dfe4; }
.intro p { font-size:15pt; margin:5px 0; color:#e8e4e9; }.cover-foot { position:absolute; bottom:.45in; left:.95in; color:#c0bac5; font-size:10pt; }
.three { display:grid; grid-template-columns:repeat(3,1fr); gap:18px; margin-top:38px; }
.two { display:grid; grid-template-columns:1fr 1fr; gap:20px; margin-top:30px; }
.card { background:white; border-top:5px solid #ad1f52; border-radius:10px; padding:18px 19px; min-height:1.62in; box-shadow:0 1px 5px #dcd6d1; }
.card:nth-child(3n+2) { border-color:#d1783b; }.card:nth-child(3n) { border-color:#477b9b; }
.card h2 { font-size:16pt; margin:0 0 10px; }.card p { font-size:11pt; line-height:1.38; margin:5px 0; color:#50535a; }
.note { background:#f0e8e9; border-radius:10px; margin-top:20px; padding:13px 20px; }.note h2 { font-size:15pt; margin:0 0 5px; }.note p { margin:5px 0; font-size:10.5pt; }.note small { font-size:9pt; color:#666; }
.slide-2 .three { margin-top:25px; }.slide-2 .card { min-height:1.7in; }.footnote { margin-top:18px; font-size:10pt; color:#666; }
.slide-3 .card,.slide-4 .card { min-height:1.8in; }.slide-3 .two,.slide-4 .two { grid-template-rows:1fr 1fr; }
.slide-5 .three { margin-top:38px; }.slide-5 .card { min-height:1.65in; }
.slide-6 .three { margin-top:24px; }.slide-6 .card { min-height:1.52in; }.slide-6 .lower { margin-top:16px; }.slide-6 .lower .card { min-height:1.35in; }
.web { margin:42px 0 0; background:white; border-top:6px solid #ad1f52; border-radius:10px; padding:28px; }
.web h2 { font-size:16pt; color:#ad1f52; margin:0 0 17px; }.web a { font-size:19pt; font-weight:700; color:#282b35; text-decoration:underline; }
.closing { margin-top:28px; font-size:11pt; color:#555; }.closing p { margin:8px 0; }
'''
document = '<!doctype html><html lang="hi"><meta charset="utf-8"><title>Naari Kavach - Hindi Features</title><style>'+style+'</style><body>'+''.join(parts)+'</body></html>'
HTML_PATH.write_text(document, encoding="utf-8")
print(HTML_PATH)
