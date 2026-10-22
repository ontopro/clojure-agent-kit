(ns svg-trace
  "An icon traced from a picture of it: the pixels of a region, the strokes found in them, and a
  24-grid SVG drawn with those strokes - the SVG pack's `trace` (`svg-trace-command` reads the PNG
  and writes the files; everything here is pure, and runs under bb as on the JVM).

  WHY TRACE. A site's icons drawn by hand from a picture - freehand paths, by eye - failed round
  after round in the first project: proportions drifted and strokes wandered. Following the
  picture's own strokes does not: the ink is found, thinned to its centre line, and each line of it
  fitted as the simplest shape that holds - a straight line, a circle, an arc, else a smoothed
  spline. That project's sixteen traced icons were approved by its owner.

  HOW. The region's ink (`:saturation` - colour against white, the default - or `:darkness` - black
  line art) is upsampled, blurred once and thresholded into a mask; Zhang-Suen thinning leaves its
  skeleton; the skeleton is cut at its junctions into chains; each chain is fitted. The result is
  fitted to the 24-unit grid with a margin and drawn at the stroke asked for, with only `path` and
  `circle` elements, `stroke=\"currentColor\"`, round caps and joins, and `fill=\"none\"` on the `svg` -
  the icon contract. An element may carry `fill=\"currentColor\"`: a dot, and any shape the picture
  fills, which tracing cannot follow (it thins a filled area to a line) and an icon's `:add` draws.

  THE PARAMETERS ARE PER ICON, and a picture's fills are not strokes: where tracing cannot follow
  a shape, the icon's entry names regions to leave out (`:exclude`) and elements to add (`:add`),
  in the picture's own pixels. Lengths and tolerances are in units of the picture drawn 600 wide,
  so one setting means the same at any crop size."
  (:require [clojure.string :as str]))

(def frame
  "Lengths and tolerances are in units of the picture drawn this wide."
  600.0)

;; ---------------------------------------------------------------------------
;; pixels -> mask -> skeleton
;; ---------------------------------------------------------------------------

(defn ink-grid
  "`{:w :h :d}` - per pixel, how much ink: `rgb` is `(fn [x y] [r g b])` over a `w` by `h` picture;
  `:saturation` is colour against white (max - min of r, g, b), `:darkness` is 255 less the mean."
  [w h rgb ink]
  (let [d (double-array (* w h))]
    (dotimes [y h]
      (dotimes [x w]
        (let [[r g b] (rgb x y)]
          (aset d (+ (* y w) x)
                (double (case ink
                          :darkness (- 255 (/ (+ r g b) 3.0))
                          (- (max r g b) (min r g b))))))))
    {:w w :h h :d d}))

(defn make-mask
  "The ink of `region` (`[x0 y0 x1 y1]`, inclusive) upsampled `up` times, blurred once (3x3) and
  thresholded at `t`: `{:a long-array :w :h}`, 1 for ink."
  [{:keys [w h d]} [x0 y0 x1 y1] up t]
  (let [rw (inc (- x1 x0)) rh (inc (- y1 y0)) UW (* rw up) UH (* rh up)
        v (double-array (* UW UH)) a (long-array (* UW UH))
        dv (fn [x y] (aget d (+ (* (max 0 (min (dec h) y)) w) (max 0 (min (dec w) x)))))]
    (dotimes [Y UH]
      (dotimes [X UW]
        (let [sx (+ x0 (- (/ (+ X 0.5) up) 0.5)) sy (+ y0 (- (/ (+ Y 0.5) up) 0.5))
              ix (long (Math/floor sx)) iy (long (Math/floor sy)) fx (- sx ix) fy (- sy iy)]
          (aset v (+ (* Y UW) X)
                (+ (* (- 1 fx) (- 1 fy) (dv ix iy)) (* fx (- 1 fy) (dv (inc ix) iy))
                   (* (- 1 fx) fy (dv ix (inc iy))) (* fx fy (dv (inc ix) (inc iy))))))))
    (let [c (aclone v)]
      (doseq [Y (range 1 (dec UH)) X (range 1 (dec UW))]
        (let [i (+ (* Y UW) X)]
          (aset v i (/ (+ (aget c (- i UW 1)) (aget c (- i UW)) (aget c (+ (- i UW) 1))
                          (aget c (- i 1)) (aget c i) (aget c (+ i 1))
                          (aget c (+ i UW -1)) (aget c (+ i UW)) (aget c (+ i UW 1)))
                       9.0)))))
    (dotimes [Y UH]
      (dotimes [X UW]
        (when (and (>= (aget v (+ (* Y UW) X)) t) (> X 1) (> Y 1) (< X (- UW 2)) (< Y (- UH 2)))
          (aset a (+ (* Y UW) X) 1))))
    {:a a :w UW :h UH}))

(defn- zs-pass! [^longs a w h step]
  (let [del (java.util.ArrayList.)]
    (doseq [y (range 1 (dec h)) x (range 1 (dec w))]
      (let [i (+ (* y w) x)]
        (when (= 1 (aget a i))
          (let [p2 (aget a (- i w)) p3 (aget a (+ (- i w) 1)) p4 (aget a (+ i 1)) p5 (aget a (+ i w 1))
                p6 (aget a (+ i w)) p7 (aget a (+ i w -1)) p8 (aget a (- i 1)) p9 (aget a (- (- i w) 1))
                b (+ p2 p3 p4 p5 p6 p7 p8 p9)
                tr (fn [u v] (if (and (= u 0) (= v 1)) 1 0))
                n (+ (tr p2 p3) (tr p3 p4) (tr p4 p5) (tr p5 p6) (tr p6 p7) (tr p7 p8) (tr p8 p9) (tr p9 p2))]
            (when (and (<= 2 b 6) (= n 1)
                       (if (= step 1)
                         (and (zero? (* p2 p4 p6)) (zero? (* p4 p6 p8)))
                         (and (zero? (* p2 p4 p8)) (zero? (* p2 p6 p8)))))
              (.add del (long i)))))))
    (doseq [i del] (aset a (long i) 0))
    (.size del)))

(defn thin!
  "Zhang-Suen thinning of the mask, in place, to its one-pixel skeleton."
  [{:keys [a w h]}]
  (loop [iter 0]
    (let [c1 (zs-pass! a w h 1) c2 (zs-pass! a w h 2)]
      (when (and (pos? (+ c1 c2)) (< iter 80)) (recur (inc iter))))))

(def ^:private nb8 [[-1 -1] [0 -1] [1 -1] [-1 0] [1 0] [-1 1] [0 1] [1 1]])
(def ^:private ring8 [[0 -1] [1 -1] [1 0] [1 1] [0 1] [-1 1] [-1 0] [-1 -1]])

(defn skeleton-chains
  "The skeleton cut at its junctions into chains of points, each with the junction centres it
  meets added at its ends: `[{:pts [[x y] ..] :j0 :j1}]`, `:j0`/`:j1` the junction met or nil."
  [{:keys [a w h]}]
  (let [on? (fn [x y] (and (>= x 0) (< x w) (>= y 0) (< y h) (= 1 (aget ^longs a (+ (* y w) x)))))
        pts (vec (for [y (range h) x (range w) :when (on? x y)] [x y]))
        crossings (fn [[x y]] (let [v (mapv (fn [[dx dy]] (if (on? (+ x dx) (+ y dy)) 1 0)) ring8)]
                                (reduce + (for [i (range 8)] (if (and (= 0 (v i)) (= 1 (v (mod (inc i) 8)))) 1 0)))))
        junction (set (filter #(>= (crossings %) 3) pts))
        label (loop [todo (seq junction) lab {} k 0]
                (if-let [p (first todo)]
                  (if (lab p)
                    (recur (rest todo) lab k)
                    (let [group (loop [st [p] seen #{p}]
                                  (if (empty? st)
                                    seen
                                    (let [[x y] (peek st)
                                          nxt (for [[dx dy] nb8 :let [q [(+ x dx) (+ y dy)]] :when (and (junction q) (not (seen q)))] q)]
                                      (recur (into (pop st) nxt) (into seen nxt)))))]
                      (recur (rest todo) (into lab (map (fn [q] [q k]) group)) (inc k))))
                  lab))
        centre (into {} (for [[k ps] (group-by val label)]
                          [k (let [qs (map key ps)] [(/ (reduce + (map first qs)) (count qs)) (/ (reduce + (map second qs)) (count qs))])]))
        others (set (remove junction pts))
        visited (atom #{})
        nbrs (fn [p] (for [[dx dy] nb8 :let [q [(+ (first p) dx) (+ (second p) dy)]] :when (others q)] q))
        walk (fn [start]
               (loop [cur start chain [start]]
                 (swap! visited conj cur)
                 (let [cands (remove @visited (nbrs cur))
                       ortho (filter (fn [q] (= 1 (+ (Math/abs (long (- (first q) (first cur)))) (Math/abs (long (- (second q) (second cur))))))) cands)
                       nxt (first (or (seq ortho) (seq cands)))]
                   (if nxt (recur nxt (conj chain nxt)) chain))))
        ends (filter #(<= (count (nbrs %)) 1) others)
        chains (loop [starts (concat ends (sort others)) out []]
                 (if-let [s (first starts)]
                   (if (@visited s) (recur (rest starts) out) (recur (rest starts) (conj out (walk s))))
                   out))
        near (fn [p] (some (fn [[dx dy]] (label [(+ (first p) dx) (+ (second p) dy)])) nb8))]
    (mapv (fn [chain]
            (let [j0 (near (first chain)) j1 (near (last chain))]
              {:pts (vec (concat (when j0 [(centre j0)]) chain (when j1 [(centre j1)]))) :j0 j0 :j1 j1}))
          chains)))

;; ---------------------------------------------------------------------------
;; fitting
;; ---------------------------------------------------------------------------

(defn- smooth [pts k]
  (let [v (vec pts) c (count v)]
    (if (< c (+ 3 (* 2 k)))
      v
      (vec (for [i (range c)]
             (if (or (< i k) (>= i (- c k)))
               (v i)
               (let [win (map v (range (- i k) (+ i k 1)))]
                 [(/ (reduce + (map first win)) (count win)) (/ (reduce + (map second win)) (count win))])))))))

(defn- dist-to-segment [[px py] [ax ay] [bx by]]
  (let [dx (- bx ax) dy (- by ay) l2 (+ (* dx dx) (* dy dy))]
    (if (zero? l2)
      (Math/hypot (- px ax) (- py ay))
      (let [t (max 0 (min 1 (/ (+ (* (- px ax) dx) (* (- py ay) dy)) l2)))]
        (Math/hypot (- px (+ ax (* t dx))) (- py (+ ay (* t dy))))))))

(defn simplify
  "Douglas-Peucker: the fewest of `pts` within `eps` of them all."
  [pts eps]
  (if (< (count pts) 3)
    (vec pts)
    (let [a (first pts) b (last pts)
          [dm im] (apply max-key first (map-indexed (fn [i p] [(dist-to-segment p a b) i]) pts))]
      (if (> dm eps)
        (let [l (simplify (subvec (vec pts) 0 (inc im)) eps) r (simplify (subvec (vec pts) im) eps)]
          (vec (concat (butlast l) r)))
        [a b]))))

(defn fit-circle
  "The circle about `pts`' centroid at their mean radius: `{:c [x y] :r :dev}`, `:dev` the worst miss."
  [pts]
  (let [n (count pts) cx (/ (reduce + (map first pts)) n) cy (/ (reduce + (map second pts)) n)
        rs (map (fn [[x y]] (Math/hypot (- x cx) (- y cy))) pts) r (/ (reduce + rs) n)]
    {:c [cx cy] :r r :dev (apply max (map #(Math/abs (double (- % r))) rs))}))

(defn- det3 [m]
  (- (+ (* (get-in m [0 0]) (get-in m [1 1]) (get-in m [2 2])) (* (get-in m [0 1]) (get-in m [1 2]) (get-in m [2 0]))
        (* (get-in m [0 2]) (get-in m [1 0]) (get-in m [2 1])))
     (+ (* (get-in m [0 2]) (get-in m [1 1]) (get-in m [2 0])) (* (get-in m [0 0]) (get-in m [1 2]) (get-in m [2 1]))
        (* (get-in m [0 1]) (get-in m [1 0]) (get-in m [2 2])))))

(defn kasa
  "The least-squares circle through `pts` (Kåsa's fit), or nil: `{:c :r :dev}`."
  [pts]
  (let [n (count pts) sum #(reduce + (map % pts))
        sx (sum first) sy (sum second)
        sxx (sum #(* (first %) (first %))) syy (sum #(* (second %) (second %))) sxy (sum #(* (first %) (second %)))
        sxxx (sum #(Math/pow (first %) 3)) syyy (sum #(Math/pow (second %) 3))
        sxxy (sum #(* (first %) (first %) (second %))) sxyy (sum #(* (first %) (second %) (second %)))
        m [[sxx sxy sx] [sxy syy sy] [sx sy n]]
        b [(- (+ sxxx sxyy)) (- (+ sxxy syyy)) (- (+ sxx syy))]
        dm (det3 m)]
    (when (> (Math/abs (double dm)) 1e-9)
      (let [sol (fn [k] (/ (det3 (vec (map-indexed (fn [i row] (assoc row k (b i))) m))) dm))
            cx (/ (sol 0) -2) cy (/ (sol 1) -2) r2 (- (+ (* cx cx) (* cy cy)) (sol 2))]
        (when (pos? r2)
          (let [r (Math/sqrt r2)]
            {:c [cx cy] :r r :dev (apply max (map (fn [[x y]] (Math/abs (- (Math/hypot (- x cx) (- y cy)) r))) pts))}))))))

(defn- arc-of [pts {:keys [c]}]
  (let [ang (fn [[x y]] (Math/atan2 (- y (c 1)) (- x (c 0))))
        angs (map ang pts)
        delta (reduce + (map (fn [a b] (let [d (- b a)]
                                         (cond (> d Math/PI) (- d (* 2 Math/PI)) (< d (- Math/PI)) (+ d (* 2 Math/PI)) :else d)))
                             angs (rest angs)))]
    {:delta delta :large (if (> (Math/abs delta) Math/PI) 1 0) :sweep (if (pos? delta) 1 0)}))

(defn spline
  "A Catmull-Rom spline through `pts` as path commands, closed when `closed`."
  [pts closed]
  (let [v (vec pts) n (count v) g (fn [i] (if closed (v (mod i n)) (v (max 0 (min (dec n) i)))))]
    (into [["M" (v 0)]]
          (concat (for [i (range (if closed n (dec n)))]
                    (let [p0 (g (dec i)) p1 (g i) p2 (g (inc i)) p3 (g (+ i 2))]
                      ["C" [(+ (p1 0) (/ (- (p2 0) (p0 0)) 6.0)) (+ (p1 1) (/ (- (p2 1) (p0 1)) 6.0))]
                       [(- (p2 0) (/ (- (p3 0) (p1 0)) 6.0)) (- (p2 1) (/ (- (p3 1) (p1 1)) 6.0))]
                       p2]))
                  (when closed [["Z"]])))))

(defn- path-length [pts] (reduce + (map (fn [a b] (Math/hypot (- (a 0) (b 0)) (- (a 1) (b 1)))) pts (rest pts))))

(defn fit-chain
  "One chain (points in the 600 frame) as the simplest element that holds: nil when it is too short
  (a dot, with `dots?`, when it stands free), a circle when it closes on one, a line, an arc, else a
  smoothed and simplified spline (or a polyline, with `polyline?`). An element is `{:t :circle :c :r}`,
  `{:t :path :cmds [..]}` (with `:bound` points for an arc) or `{:t :dot :c}`, drawn filled at the
  stroke's width."
  [{:keys [pts j0 j1]} {:keys [epsilon min-length dots? polyline? k] :or {epsilon 2.6 min-length 10 k 1}}]
  (let [len (path-length pts)]
    (cond
      ;; a dot: a short stroke standing alone, a small blob whose skeleton is one point included
      (and (< len min-length) dots? (nil? j0) (nil? j1) (seq pts)) {:t :dot :c (:c (fit-circle pts))}
      (or (< (count pts) 2) (< len min-length)) nil
      :else
      (let [closed (< (Math/hypot (- (get-in pts [0 0]) (get-in (peek pts) [0])) (- (get-in pts [0 1]) (get-in (peek pts) [1]))) (* 1.6 k))
            cf (when closed (fit-circle pts))]
        (if (and closed (> (count pts) 14) (< (:dev cf) (* 0.09 (:r cf))))
          {:t :circle :c (:c cf) :r (:r cf)}
          (let [ld (apply max (map #(dist-to-segment % (first pts) (peek pts)) pts))
                kf (kasa (vec (take-nth 2 pts)))
                arc (when kf (arc-of pts kf))]
            (cond
              (< ld (max 3.0 (* 0.018 len)))
              {:t :path :cmds [["M" (first pts)] ["L" (peek pts)]]}

              (and kf (< (:dev kf) (max 3.2 (* 0.03 (:r kf)))) (< 6 (:r kf) 700) (> (Math/abs (double (:delta arc))) 0.45))
              (let [a0 (Math/atan2 (- (get-in pts [0 1]) (get-in kf [:c 1])) (- (get-in pts [0 0]) (get-in kf [:c 0])))]
                {:t :path :cmds [["M" (first pts)] ["A" (:r kf) (:large arc) (:sweep arc) (peek pts)]]
                 :bound (vec (for [i (range 13) :let [a (+ a0 (* (:delta arc) (/ i 12.0)))]]
                               [(+ (get-in kf [:c 0]) (* (:r kf) (Math/cos a))) (+ (get-in kf [:c 1]) (* (:r kf) (Math/sin a)))]))})

              :else
              (let [k (cond (> len 260) 7 (> len 120) 5 :else 3)
                    sm (if polyline? (smooth pts 1) (smooth (smooth pts k) k))
                    sp (simplify sm epsilon)]
                (if (or (= 2 (count sp)) polyline?)
                  {:t :path :cmds (vec (cons ["M" (first sp)] (map (fn [p] ["L" p]) (rest sp))))}
                  {:t :path :cmds (spline sp (and closed (> (count sp) 3)))})))))))))

;; ---------------------------------------------------------------------------
;; an icon
;; ---------------------------------------------------------------------------

(defn- scale-element
  "An element given in the picture's pixels, in the 600 frame (`k` the factor)."
  [k {:keys [path circle fill]}]
  (cond-> (cond
            circle (let [[x y r] circle] {:t :circle :c [(* k x) (* k y)] :r (* k r)})
            path {:t :path :cmds (vec (for [[c & args] path]
                                        (case c
                                          "A" (let [[r large sweep p] args]
                                                ;; the flags are written into the path as they are: 0 or 1, never text
                                                (when-not (and (#{0 1} large) (#{0 1} sweep))
                                                  (throw (ex-info (str "an arc's flags are 0 or 1, not " (pr-str large) " and " (pr-str sweep)) {:arc args})))
                                                ["A" (* k r) large sweep (mapv #(* k %) p)])
                                          "Z" ["Z"]
                                          (into [c] (map (fn [p] (mapv #(* k %) p)) args)))))})
    fill (assoc :fill true)))

(defn trace-elements
  "The icon's elements in the 600 frame from `grid` (`ink-grid`'s) and its entry: `:region` `[x0 y0
  x1 y1]` in the picture's pixels, `:threshold`, `:epsilon`, `:min-length`, `:dots?`, `:polyline?`,
  `:exclude` (regions whose chains are dropped) and `:add` (elements in the picture's pixels:
  `{:path [[\"M\" [x y]] [\"L\" [x y]] ..]}` or `{:circle [x y r]}`, either with `:fill true` for a
  shape the picture fills). Returns `{:elements :chains :k}`."
  [grid {:keys [region threshold exclude add] :or {threshold 70} :as entry}]
  (let [up 3
        [x0 y0] region
        k (/ frame (:w grid))
        mask (make-mask grid region up threshold)
        _ (thin! mask)
        chains (skeleton-chains mask)
        to-frame (fn [[X Y]] [(* k (+ x0 (/ (+ X 0.5) up))) (* k (+ y0 (/ (+ Y 0.5) up)))])
        excluded? (fn [pts] (some (fn [[a b c d]] (every? (fn [[px py]] (and (<= a (/ px k) c) (<= b (/ py k) d))) pts)) exclude))
        traced (keep (fn [ch] (let [pts (mapv to-frame (:pts ch))]
                                (when-not (excluded? pts) (fit-chain (assoc ch :pts pts) (assoc entry :k k)))))
                     chains)]
    {:elements (vec (concat traced (map #(scale-element k %) add)))
     :chains (count chains)
     :k k}))

(defn- points-of [e]
  (case (:t e)
    :circle (let [[x y] (:c e) r (:r e)] [[(- x r) (- y r)] [(+ x r) (+ y r)]])
    :dot [(:c e)]
    :path (concat (:bound e) (mapcat (fn [[c & a]] (cond (#{"M" "L" "C"} c) a (= "A" c) [(last a)])) (:cmds e)))))

(defn grid-transform
  "How the elements are fitted to the 24 grid with a 1-unit margin: `{:s :tp}`, `tp` a point's
  transform; the box is `bbox` (in the 600 frame) when given, else the elements' own."
  [elements bbox]
  (let [ps (mapcat points-of elements)
        [bx0 by0 bx1 by1] (or bbox [(apply min (map first ps)) (apply min (map second ps))
                                    (apply max (map first ps)) (apply max (map second ps))])
        cx (/ (+ bx0 bx1) 2.0) cy (/ (+ by0 by1) 2.0)
        s (/ 22.0 (max 1e-9 (- bx1 bx0) (- by1 by0)))]
    {:s s :tp (fn [[x y]] [(+ 12 (* s (- x cx))) (+ 12 (* s (- y cy)))])}))

(defn- n2 [x] (let [s (format "%.2f" (double x))] (-> s (str/replace #"0+$" "") (str/replace #"\.$" "") (#(if (= % "-0") "0" %)))))

(defn- cmd-str [tp s [c & args]]
  (case c
    "M" (let [[x y] (tp (first args))] (str "M" (n2 x) " " (n2 y)))
    "L" (let [[x y] (tp (first args))] (str "L" (n2 x) " " (n2 y)))
    "C" (str "C" (str/join " " (map (fn [p] (let [[x y] (tp p)] (str (n2 x) " " (n2 y)))) args)))
    "Z" "Z"
    "h" "h.01"
    "A" (let [[r large sweep p] args [x y] (tp p)] (str "A" (n2 (* s r)) " " (n2 (* s r)) " 0 " large " " sweep " " (n2 x) " " (n2 y)))))

(defn element-str
  "One element as SVG, through `tp` at scale `s`, `dot-r` a dot's radius: only `path` and `circle`,
  `fill=\"currentColor\"` on a filled one."
  [tp s dot-r e]
  (let [fill (when (:fill e) " fill=\"currentColor\"")]
    (case (:t e)
      :path (str "<path d=\"" (str/join "" (map #(cmd-str tp s %) (:cmds e))) "\"" fill " />")
      :circle (let [[x y] (tp (:c e))] (str "<circle cx=\"" (n2 x) "\" cy=\"" (n2 y) "\" r=\"" (n2 (* s (:r e))) "\"" fill " />"))
      :dot (let [[x y] (tp (:c e))] (str "<circle cx=\"" (n2 x) "\" cy=\"" (n2 y) "\" r=\"" (n2 dot-r) "\" fill=\"currentColor\" stroke=\"none\" />")))))

(defn icon-svg
  "The icon: a 24 by 24 `viewBox`, no fill, `currentColor` at `stroke`, round caps and joins, its
  elements fitted to the grid."
  [elements stroke bbox]
  (let [{:keys [s tp]} (grid-transform elements bbox)]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\""
         " stroke=\"currentColor\" stroke-width=\"" (n2 stroke) "\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n"
         (str/join "\n" (map #(str "  " (element-str tp s (/ stroke 2.0) %)) elements))
         "\n</svg>\n")))

(defn overlay-svg
  "The trace drawn over the picture, for a person to judge: the picture (a PNG, base64) drawn in the
  600 frame and the elements over it in green at the icon's stroke, scaled back."
  [elements png-base64 picture-h-in-frame stroke bbox]
  (let [{:keys [s]} (grid-transform elements bbox)]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"300\" height=\"" (n2 (/ picture-h-in-frame 2)) "\" viewBox=\"0 0 600 "
         (n2 picture-h-in-frame) "\"><image width=\"600\" height=\"" (n2 picture-h-in-frame) "\" xlink:href=\"data:image/png;base64," png-base64 "\"/>"
         "<g fill=\"none\" color=\"#00b050\" stroke=\"#00b050\" stroke-opacity=\".8\" stroke-width=\"" (n2 (/ stroke s)) "\" stroke-linecap=\"round\" stroke-linejoin=\"round\">"
         (str/join "" (map #(element-str identity 1 (/ stroke s 2.0) %) elements))
         "</g></svg>")))

(defn icon
  "An icon from `grid` and its entry at `stroke`: `{:svg :elements :chains :k :bbox}`, `:bbox` the
  entry's (in the picture's pixels) in the 600 frame, or nil when the elements' own box is the one."
  [grid entry stroke]
  (let [{:keys [elements chains k]} (trace-elements grid entry)
        bbox (some->> (:bbox entry) (mapv #(* k %)))]
    {:elements elements :chains chains :k k :bbox bbox
     :svg (if (seq elements) (icon-svg elements stroke bbox) nil)}))
