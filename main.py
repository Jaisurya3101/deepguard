#!/usr/bin/env python3
"""DeepGuard v3.0 - Complete Integrated Server"""

import sys, os
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from fastapi import FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, List, Dict
from datetime import datetime
import uvicorn, uuid

from src.core.security import create_access_token, verify_password, get_password_hash
from src.utils.logging import logger

app = FastAPI(title="DeepGuard API v3.0", version="3.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

users_db = {"testuser": {"user_id": "1", "username": "testuser", "email": "test@deepguard.com", "full_name": "Test User", "hashed_password": get_password_hash("password123")}}
analytics_db = {"total_scans": 0, "threats_detected": 0, "recent_scans": []}

class SignupRequest(BaseModel):
    username: str; email: str; password: str; full_name: Optional[str] = None

class LoginRequest(BaseModel):
    username: str; password: str

class ScanRequest(BaseModel):
    text: str

class NotificationPayload(BaseModel):
    content: str; sender: Optional[str] = "unknown"; timestamp: Optional[int] = None

def enhanced_harassment_check(text: str) -> dict:
    high = ['kill', 'murder', 'die', 'threat', 'hurt', 'harm']
    medium = ['hate', 'stupid', 'idiot', 'loser', 'pathetic']
    low = ['annoying', 'weird', 'dumb', 'ugly']
    profanity = ['fuck', 'shit', 'bitch', 'ass', 'damn']
    text_lower = text.lower()
    high_m = [k for k in high if k in text_lower]
    medium_m = [k for k in medium if k in text_lower]
    low_m = [k for k in low if k in text_lower]
    prof_m = [k for k in profanity if k in text_lower]
    all_m = high_m + medium_m + low_m + prof_m
    if high_m: toxic, level, sev = 0.85, "HIGH", "critical"
    elif medium_m: toxic, level, sev = 0.65, "MEDIUM", "high"
    elif low_m: toxic, level, sev = 0.40, "LOW", "medium"
    elif prof_m: toxic, level, sev = 0.30, "LOW", "low"
    else: toxic, level, sev = 0.05, "NONE", "none"
    is_har = toxic > 0.3
    return {'toxic_score': toxic, 'is_harassment': is_har, 'threat_level': level, 'severity': sev, 'keywords': all_m, 'confidence': toxic if is_har else (1.0 - toxic)}

@app.get("/")
async def root():
    return {"message": "DeepGuard API v3.0", "status": "healthy", "version": "3.0.0", "port": 8002}

@app.get("/api/v1/health")
async def health():
    return {"status": "healthy", "message": "API operational"}

@app.post("/api/v1/auth/signup")
async def signup(r: SignupRequest):
    if r.username in users_db: raise HTTPException(400, "Username exists")
    users_db[r.username] = {"user_id": str(len(users_db)+1), "username": r.username, "email": r.email, "full_name": r.full_name or r.username, "hashed_password": get_password_hash(r.password)}
    token = create_access_token({"sub": r.username})
    user = {k:v for k,v in users_db[r.username].items() if k!="hashed_password"}
    return {"success": True, "message": "Registered", "data": {"access_token": token, "token_type": "bearer", "user": user}}

@app.post("/api/v1/auth/login")
async def login(r: LoginRequest):
    u = users_db.get(r.username)
    if not u or not verify_password(r.password, u["hashed_password"]): raise HTTPException(401, "Invalid credentials")
    token = create_access_token({"sub": r.username})
    user = {k:v for k,v in u.items() if k!="hashed_password"}
    return {"success": True, "message": "Login OK", "data": {"access_token": token, "token_type": "bearer", "user": user}}

@app.get("/api/v1/auth/me")
async def get_me(authorization: Optional[str] = Header(None)):
    if not authorization: raise HTTPException(401, "Not authenticated")
    user = {k:v for k,v in users_db["testuser"].items() if k!="hashed_password"}
    return {"success": True, "data": user}

@app.post("/api/v1/scan_text")
async def scan_text(r: ScanRequest):
    a = enhanced_harassment_check(r.text)
    analytics_db["total_scans"] += 1
    if a["is_harassment"]: analytics_db["threats_detected"] += 1
    
    # Store recent scan for dashboard/analytics
    scan_record = {"id": str(uuid.uuid4()), "text": r.text[:100], "is_harassment": a["is_harassment"], "severity": a["severity"], "threat_level": a["threat_level"], "risk_score": int(a["toxic_score"]*100), "timestamp": datetime.now().isoformat(), "keywords": a["keywords"]}
    analytics_db["recent_scans"].insert(0, scan_record)
    if len(analytics_db["recent_scans"]) > 50: analytics_db["recent_scans"] = analytics_db["recent_scans"][:50]
    
    res = {"is_harassment": a["is_harassment"], "confidence": round(a["confidence"], 3), "severity": a["severity"], "threat_level": a["threat_level"], "keywords_detected": a["keywords"], "risk_score": int(a["toxic_score"]*100), "explanation": f"Threat: {a['threat_level']}" if a["is_harassment"] else "Safe"}
    return {"success": True, "message": "Scan complete", "data": res}

@app.post("/api/v1/scan_image")
async def scan_image():
    return {"success": True, "message": "Coming soon", "data": {"is_deepfake": False, "confidence": 0.0}}

@app.get("/api/v1/user/stats")
async def get_user_stats(authorization: Optional[str] = Header(None), period: str = "week"):
    """Get user statistics - matches StatsResponse model"""
    from collections import defaultdict
    
    # User stats
    user_stats = {
        "total_scans": analytics_db["total_scans"],
        "harassment_detected": analytics_db["threats_detected"],
        "deepfakes_detected": 0,
        "safety_score": round((1 - analytics_db["threats_detected"] / max(analytics_db["total_scans"], 1)) * 100, 1),
        "last_scan": analytics_db["recent_scans"][0]["timestamp"] if analytics_db["recent_scans"] else None
    }
    
    # Daily stats
    daily_data = defaultdict(lambda: {"date": "", "scans_count": 0, "harassment_count": 0, "deepfake_count": 0})
    for scan in analytics_db["recent_scans"]:
        date = scan["timestamp"].split("T")[0]
        daily_data[date]["date"] = date
        daily_data[date]["scans_count"] += 1
        if scan["is_harassment"]:
            daily_data[date]["harassment_count"] += 1
    daily_stats = sorted(daily_data.values(), key=lambda x: x["date"], reverse=True)[:7]
    
    # Weekly trend
    weekly_trend = [{
        "week": f"Week {i+1}",
        "risk_level": round((analytics_db["threats_detected"] / max(analytics_db["total_scans"], 1)) * 100, 1),
        "total_threats": analytics_db["threats_detected"]
    } for i in range(4)]
    
    # Scan summary
    scan_summary = {
        "safe_files": analytics_db["total_scans"] - analytics_db["threats_detected"],
        "ai_generated": 0,
        "harmful_media": analytics_db["threats_detected"],
        "last_updated": datetime.now().isoformat()
    }
    
    return {"success": True, "data": {
        "user_stats": user_stats,
        "daily_stats": daily_stats,
        "weekly_trend": weekly_trend,
        "scan_summary": scan_summary
    }}

@app.post("/api/v1/mobile/analyze-notification")
async def analyze_notification(p: NotificationPayload):
    a = enhanced_harassment_check(p.content)
    analytics_db["total_scans"] += 1
    if a["is_harassment"]: analytics_db["threats_detected"] += 1
    
    # Store recent scan for dashboard/analytics
    scan_record = {"id": str(uuid.uuid4()), "text": p.content[:100], "sender": p.sender, "is_harassment": a["is_harassment"], "severity": a["severity"], "threat_level": a["threat_level"], "risk_score": int(a["toxic_score"]*100), "timestamp": datetime.now().isoformat(), "keywords": a["keywords"]}
    analytics_db["recent_scans"].insert(0, scan_record)
    if len(analytics_db["recent_scans"]) > 50: analytics_db["recent_scans"] = analytics_db["recent_scans"][:50]
    
    risk = int(a["toxic_score"]*100)
    return {"harassment": {"is_harassment": a["is_harassment"], "confidence": round(a["confidence"], 3), "type": "threat" if a["is_harassment"] else "safe", "severity": a["severity"], "keywords_detected": a["keywords"], "explanation": f"HARASSMENT: {risk}% risk" if a["is_harassment"] else "SAFE"}, "analysis_id": str(uuid.uuid4()), "timestamp": p.timestamp or int(datetime.now().timestamp()*1000), "risk_score": risk, "threat_level": a["threat_level"], "detection_method": "keyword_enhanced"}

@app.get("/api/v1/analytics/overview")
async def get_analytics():
    safe = analytics_db["total_scans"] - analytics_db["threats_detected"]
    
    # Calculate threat breakdown for pie chart
    high_threats = len([s for s in analytics_db["recent_scans"] if s.get("threat_level") == "HIGH"])
    medium_threats = len([s for s in analytics_db["recent_scans"] if s.get("threat_level") == "MEDIUM"])
    low_threats = len([s for s in analytics_db["recent_scans"] if s.get("threat_level") == "LOW"])
    
    # Calculate severity breakdown
    critical = len([s for s in analytics_db["recent_scans"] if s.get("severity") == "critical"])
    high_sev = len([s for s in analytics_db["recent_scans"] if s.get("severity") == "high"])
    medium_sev = len([s for s in analytics_db["recent_scans"] if s.get("severity") == "medium"])
    low_sev = len([s for s in analytics_db["recent_scans"] if s.get("severity") == "low"])
    
    return {"success": True, "data": {"total_scans": analytics_db["total_scans"], "threats_detected": analytics_db["threats_detected"], "threats_blocked": analytics_db["threats_detected"], "safe_messages": safe, "detection_rate": round((analytics_db["threats_detected"] / max(analytics_db["total_scans"], 1)) * 100, 1), "threat_breakdown": {"high": high_threats, "medium": medium_threats, "low": low_threats}, "severity_breakdown": {"critical": critical, "high": high_sev, "medium": medium_sev, "low": low_sev}, "recent_activity": analytics_db["recent_scans"][:10]}}

@app.get("/api/v1/analytics/stats")
async def get_stats():
    """Detailed stats for graphs and charts"""
    from collections import Counter
    
    # Top keywords detected
    all_keywords = []
    for scan in analytics_db["recent_scans"]:
        all_keywords.extend(scan.get("keywords", []))
    keyword_counts = Counter(all_keywords).most_common(10)
    
    # Hourly activity (simplified - in production use proper time grouping)
    hourly_data = [{"hour": i, "scans": 0, "threats": 0} for i in range(24)]
    for scan in analytics_db["recent_scans"]:
        try:
            hour = int(scan["timestamp"].split("T")[1].split(":")[0])
            hourly_data[hour]["scans"] += 1
            if scan["is_harassment"]: hourly_data[hour]["threats"] += 1
        except: pass
    
    return {"success": True, "data": {"hourly_activity": hourly_data, "top_keywords": [{"keyword": k, "count": c} for k, c in keyword_counts], "total_scans": analytics_db["total_scans"], "threat_percentage": round((analytics_db["threats_detected"] / max(analytics_db["total_scans"], 1)) * 100, 1)}}

@app.get("/api/v1/analytics/dashboard")
async def get_dashboard_analytics(authorization: Optional[str] = Header(None), days: int = 7):
    """Dashboard analytics endpoint for Android app - matches StatsResponse model"""
    from collections import defaultdict
    
    # User stats
    user_stats = {
        "total_scans": analytics_db["total_scans"],
        "harassment_detected": analytics_db["threats_detected"],
        "deepfakes_detected": 0,
        "safety_score": round((1 - analytics_db["threats_detected"] / max(analytics_db["total_scans"], 1)) * 100, 1),
        "last_scan": analytics_db["recent_scans"][0]["timestamp"] if analytics_db["recent_scans"] else None
    }
    
    # Daily stats - group by date
    daily_data = defaultdict(lambda: {"date": "", "scans_count": 0, "harassment_count": 0, "deepfake_count": 0})
    for scan in analytics_db["recent_scans"]:
        date = scan["timestamp"].split("T")[0]
        daily_data[date]["date"] = date
        daily_data[date]["scans_count"] += 1
        if scan["is_harassment"]:
            daily_data[date]["harassment_count"] += 1
    daily_stats = sorted(daily_data.values(), key=lambda x: x["date"], reverse=True)[:7]
    
    # Weekly trend
    weekly_trend = [{
        "week": f"Week {i+1}",
        "risk_level": round((analytics_db["threats_detected"] / max(analytics_db["total_scans"], 1)) * 100, 1),
        "total_threats": analytics_db["threats_detected"]
    } for i in range(4)]
    
    # Scan summary
    scan_summary = {
        "safe_files": analytics_db["total_scans"] - analytics_db["threats_detected"],
        "ai_generated": 0,
        "harmful_media": analytics_db["threats_detected"],
        "last_updated": datetime.now().isoformat()
    }
    
    return {"success": True, "data": {
        "user_stats": user_stats,
        "daily_stats": daily_stats,
        "weekly_trend": weekly_trend,
        "scan_summary": scan_summary
    }}

@app.get("/api/v1/analytics/trends")
async def get_trends(authorization: Optional[str] = Header(None), period: str = "month"):
    """Weekly/monthly trend data for charts - returns List<WeeklyTrend>"""
    from collections import defaultdict
    
    # Group scans by week
    weekly_data = defaultdict(lambda: {"week": "", "risk_level": 0.0, "total_threats": 0, "total_scans": 0})
    for scan in analytics_db["recent_scans"]:
        # Simplified week grouping - in production use proper datetime
        date = scan["timestamp"].split("T")[0]
        week_key = date[:7]  # YYYY-MM as week identifier
        weekly_data[week_key]["week"] = week_key
        weekly_data[week_key]["total_scans"] += 1
        if scan["is_harassment"]:
            weekly_data[week_key]["total_threats"] += 1
    
    # Calculate risk level for each week
    trends = []
    for week_data in weekly_data.values():
        risk_level = round((week_data["total_threats"] / max(week_data["total_scans"], 1)) * 100, 1)
        trends.append({
            "week": week_data["week"],
            "risk_level": risk_level,
            "total_threats": week_data["total_threats"]
        })
    
    trends = sorted(trends, key=lambda x: x["week"], reverse=True)[:12]
    return {"success": True, "data": trends}

@app.get("/api/v1/analytics/summary")
async def get_scan_summary(authorization: Optional[str] = Header(None)):
    """Scan summary with detailed breakdown - returns ScanSummary"""
    safe = analytics_db["total_scans"] - analytics_db["threats_detected"]
    
    # Threat type breakdown for pie chart
    harassment_types = {"cyberbullying": 0, "hate_speech": 0, "threats": 0, "profanity": 0}
    for scan in analytics_db["recent_scans"]:
        if scan["is_harassment"]:
            keywords = scan.get("keywords", [])
            if any(k in ['kill', 'murder', 'die', 'threat'] for k in keywords):
                harassment_types["threats"] += 1
            elif any(k in ['hate', 'stupid', 'idiot'] for k in keywords):
                harassment_types["hate_speech"] += 1
            elif any(k in ['fuck', 'shit', 'bitch'] for k in keywords):
                harassment_types["profanity"] += 1
            else:
                harassment_types["cyberbullying"] += 1
    
    return {"success": True, "data": {
        "safe_files": safe,
        "ai_generated": 0,
        "harmful_media": analytics_db["threats_detected"],
        "last_updated": datetime.now().isoformat(),
        "threat_breakdown": harassment_types,
        "average_risk_score": round(sum(s.get("risk_score", 0) for s in analytics_db["recent_scans"]) / max(len(analytics_db["recent_scans"]), 1), 1)
    }}

if __name__ == "__main__":
    print("\n" + "="*60)
    print("DeepGuard v3.0 - Complete Integrated Server")
    print("="*60)
    print("Port: 8002")
    print("URL: http://0.0.0.0:8002")
    print("Docs: http://localhost:8002/docs")
    print("="*60 + "\n")
    uvicorn.run(app, host="0.0.0.0", port=8002)
