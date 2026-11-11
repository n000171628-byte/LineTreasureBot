"""Prototype of the detector (adaptive tile size) that BotLoop/TileVision.kt ports.

Usage: python3 tile_prototype.py shot1.jpg shot2.jpg ...
Tile size is measured per screenshot (median width of near-square cream blobs), because it changes per level.
"""
import sys
import cv2
import numpy as np
def bmask(im):
    h,s,v=cv2.split(cv2.cvtColor(im,cv2.COLOR_BGR2HSV))
    return ((h>=18)&(h<=40)&(s>=25)&(s<=120)&(v>=205)).astype(np.uint8)*255

def board_tiles(im, k=5):
    s=im.shape[1]/1189.0
    m=bmask(im); m[:int(330*s)]=0; m[int(2000*s):]=0
    kk=np.ones((k,k),np.uint8); m=cv2.morphologyEx(m,cv2.MORPH_CLOSE,kk)
    n,lab,st,_=cv2.connectedComponentsWithStats(m)
    blobs=[tuple(map(int,b)) for b in st[1:] if b[4]>2500*s*s]
    # tile width = median width of blobs with near-square aspect
    ws=[w for x,y,w,h,a in blobs if 0.95<=w/h<=1.15 and 80*s<=w<=250*s]
    if not ws: return [],0
    tw=int(np.median(ws)); th=int(tw*0.96)
    pitch=tw*1.075
    tiles=[]
    for x,y,w,h,a in blobs:
        cols=max(1,round((w-tw)/pitch)+1) if w>tw*1.3 else 1
        rows=max(1,round((h-th)/pitch)+1) if h>th*1.3 else 1
        for r in range(rows):
            for c in range(cols):
                tx=x+(c*(w-tw)/(cols-1) if cols>1 else 0); ty=y+(r*(h-th)/(rows-1) if rows>1 else 0)
                tiles.append((int(tx),int(ty),tw,th))
    return tiles,tw

def patches(im,t):
    x,y,w,h=t
    big=cv2.resize(im[y:y+h,x:x+w],(64,62),interpolation=cv2.INTER_AREA)
    return big[14:51,14:50], big[7:58,7:57]
def sim(a,b):
    return max(cv2.matchTemplate(b[1],a[0],cv2.TM_CCOEFF_NORMED).max(), cv2.matchTemplate(a[1],b[0],cv2.TM_CCOEFF_NORMED).max())
def group(ps,thr=0.85):
    labs=[];reps=[]
    for p in ps:
        sc=[sim(p,r) for r in reps]
        if sc and max(sc)>=thr: labs.append(int(np.argmax(sc)))
        else: reps.append(p); labs.append(len(reps)-1)
    return labs,reps

def slot_cells(im):
    s=im.shape[1]/1189.0
    m=bmask(im); out=[]
    for i in range(7):
        cx=(213.2+126.9*i)*s
        x0,x1=int(cx-63*s),int(cx+63*s); y0,y1=int(1995*s),int(2165*s)
        cell=m[y0:y1,x0:x1]
        frac=cell.mean()/255
        ys,xs=np.where(cell>0)
        if frac>0.12 and len(xs)>0:
            bx,by,bw,bh=xs.min()+x0,ys.min()+y0,xs.max()-xs.min()+1,ys.max()-ys.min()+1
            out.append((i,round(frac,2),(bx,by,bw,bh)))
        else: out.append((i,round(frac,2),None))
    return out

if __name__=='__main__':
    for path in sys.argv[1:]:
        im=cv2.imread(path)
        tiles,tw=board_tiles(im)
        if not tiles:
            print(path,'no tiles'); continue
        labs,reps=group([patches(im,t) for t in tiles])
        print(path,'tile width',tw,'tiles',len(tiles),'kinds',len(reps),'group sizes',sorted(np.bincount(labs).tolist(),reverse=True))
        for i,fr,bb in slot_cells(im):
            if bb is not None: print('  slot',i,'occupied')
