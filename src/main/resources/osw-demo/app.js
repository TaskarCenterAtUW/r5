// Walkshed explorer: pick a pedestrian cost profile, set its limits, click a starting point.
// Talks to OswDemoServer (/api/info, /api/network, /api/walkshed).

(function () {
  'use strict';

  // Near (dark) to far (light). Up to six bands spanning the time limit, on whole-minute boundaries.
  const BAND_COLORS = ['#08306b', '#08519c', '#2171b5', '#4292c6', '#6baed6', '#9ecae1'];
  const BARRIER = '#c2185b';
  const UNREACHED = '#9aa6b2';
  const OTHER = '#ccd3da';
  const INK = '#16212b';

  // Band width in minutes: the smallest "nice" step giving at most six bands.
  function bands () {
    const step = [1, 2, 3, 5, 10, 15, 20, 30].find((s) => state.maxMinutes / s <= BAND_COLORS.length) || 30;
    const n = Math.ceil(state.maxMinutes / step);
    // Spread the bands across the whole ramp so short limits still use dark and light ends.
    const colors = Array.from({length: n}, (_, i) =>
      BAND_COLORS[n === 1 ? 0 : Math.round(i * (BAND_COLORS.length - 1) / (n - 1))]);
    return {step, colors};
  }

  const $ = (id) => document.getElementById(id);
  // The controls follow the TDEI Walksheds page: its sliders and switches, and a cost limit in seconds at 1.3 m/s.
  const WALK_SPEED = 1.3;
  const state = {
    info: null, profileKey: null, params: {}, origin: null, maxCost: 300,
    get maxMinutes () { return this.maxCost / 60; }
  };
  let map, originMarker, walkshedRequest = 0, networkRequest = 0;

  // ---------------------------------------------------------------------------------------------- URL state

  function readHash () {
    try {
      const h = new URLSearchParams(location.hash.slice(1));
      if (h.get('profile')) state.profileKey = h.get('profile');
      if (h.get('params')) state.params = JSON.parse(h.get('params'));
      if (h.get('origin')) {
        const [lon, lat] = h.get('origin').split(',').map(Number);
        if (isFinite(lon) && isFinite(lat)) state.origin = {lon, lat};
      }
      if (h.get('cost')) state.maxCost = clampCost(Number(h.get('cost')));
      else if (h.get('minutes')) state.maxCost = clampCost(Number(h.get('minutes')) * 60); // older links
    } catch (e) { /* ignore a malformed link */ }
  }

  function writeHash () {
    const h = new URLSearchParams();
    h.set('profile', state.profileKey);
    h.set('params', JSON.stringify(state.params));
    h.set('cost', state.maxCost);
    if (state.origin) h.set('origin', state.origin.lon.toFixed(7) + ',' + state.origin.lat.toFixed(7));
    history.replaceState(null, '', '#' + h.toString());
  }

  // ---------------------------------------------------------------------------------------------- Formatting

  function formatValue (spec, value) {
    if (spec.type === 'boolean') return value ? 'Yes' : 'No';
    if (spec.unit === 'grade') return (value * 100).toFixed(1).replace(/\.0$/, '') + '%';
    if (spec.unit === 'seconds') return value + ' s';
    return spec.unit ? value + ' ' + spec.unit : String(value);
  }

  function clampCost (seconds) {
    return isFinite(seconds) && seconds > 0 ? Math.min(3600, Math.max(100, Math.round(seconds / 100) * 100)) : 300;
  }

  function formatMinutes (minutes) {
    return String(Math.round(minutes * 10) / 10);
  }

  function formatDistance (meters) {
    return meters >= 1000 ? (meters / 1000).toFixed(1) + ' km' : meters + ' m';
  }

  function short (id) { return id ? id.slice(0, 8) : '–'; }

  // ---------------------------------------------------------------------------------------------- Panel

  function currentProfile () {
    return state.info.profiles.find((p) => p.key === state.profileKey);
  }

  function renderProfiles () {
    const select = $('profile');
    select.innerHTML = '';
    for (const p of state.info.profiles) {
      const o = document.createElement('option');
      o.value = p.key;
      o.textContent = p.error ? p.name + ' (has errors)' : p.name;
      o.disabled = !!p.error;
      select.appendChild(o);
    }
    const usable = state.info.profiles.filter((p) => !p.error);
    if (!currentProfile() || currentProfile().error) {
      // Start with the production Walksheds cost function where it is available.
      const preferred = usable.find((p) => p.key === 'ws-prod') || usable[0];
      state.profileKey = preferred ? preferred.key : null;
    }
    select.value = state.profileKey;
  }

  function renderParameters () {
    const profile = currentProfile();
    $('profile-description').textContent = profile.description || '';
    $('profile-id').textContent = short(profile.profileId);
    $('profile-id').dataset.full = profile.profileId;
    const container = $('parameters');
    container.innerHTML = '';
    const params = profile.parameters || {};
    // Use values from the link (or the user's earlier settings for this profile); fill the rest with defaults.
    const values = {};
    for (const [name, spec] of Object.entries(params)) {
      values[name] = (name in state.params) ? state.params[name] : spec.default;
    }
    state.params = values;
    // A profile can keep a parameter off the page; it stays at its default (or the value in the link).
    const shown = Object.entries(params).filter(([, spec]) => !spec.hidden);
    if (shown.length === 0) {
      container.innerHTML = '<p class="quiet small">This profile has no adjustable limits.</p>';
      return;
    }
    for (const [name, spec] of shown) {
      const id = 'param-' + name;
      const label = spec.label || name.replace(/_/g, ' ');
      const field = document.createElement('div');
      if (spec.type === 'boolean') {
        field.className = 'field check';
        field.innerHTML = `<input type="checkbox" role="switch" id="${id}"><label for="${id}"></label>`;
        field.querySelector('label').textContent = label;
        const input = field.querySelector('input');
        input.checked = !!values[name];
        input.addEventListener('change', () => { state.params[name] = input.checked; changed(); });
      } else {
        field.className = 'field range';
        field.innerHTML = `<label for="${id}"></label><output for="${id}"></output><input type="range" id="${id}">`;
        field.querySelector('label').textContent = label;
        const input = field.querySelector('input');
        const out = field.querySelector('output');
        // A profile may give the slider a narrower range than the values it accepts.
        const min = spec.sliderMin ?? spec.min ?? 0;
        const max = spec.sliderMax ?? spec.max ?? Math.max(1, (spec.default || 1) * 4);
        input.min = min;
        input.max = max;
        input.step = spec.step ?? (max - min) / 100;
        input.value = values[name];
        out.textContent = formatValue(spec, Number(input.value));
        input.addEventListener('input', () => {
          state.params[name] = Number(input.value);
          out.textContent = formatValue(spec, state.params[name]);
          changed();
        });
      }
      if (spec.description) field.title = spec.description;
      container.appendChild(field);
    }
  }

  function renderTrip () {
    const slider = $('max-cost'), number = $('max-cost-number');
    slider.value = number.value = state.maxCost;
    const set = (seconds) => {
      state.maxCost = clampCost(seconds);
      slider.value = number.value = state.maxCost;
      renderLegend();
      changed();
    };
    slider.addEventListener('input', () => set(Number(slider.value)));
    number.addEventListener('change', () => set(Number(number.value)));
  }

  function renderLegend () {
    const list = $('legend-bands');
    list.innerHTML = '';
    const {step, colors} = bands();
    colors.forEach((c, i) => {
      const li = document.createElement('li');
      li.style.setProperty('--c', c);
      li.textContent = formatMinutes(Math.min((i + 1) * step, state.maxMinutes));
      list.appendChild(li);
    });
    if (map && map.getLayer('walkshed')) map.setPaintProperty('walkshed', 'line-color', bandExpression());
  }

  function setResult (html, isError) {
    $('result-text').innerHTML = html;
    document.querySelector('.result').classList.toggle('error', !!isError);
  }

  // ---------------------------------------------------------------------------------------------- Map

  function bandExpression () {
    const {step, colors} = bands();
    const expr = ['step', ['/', ['+', ['get', 't0'], ['get', 't1']], 2], colors[0]];
    for (let i = 1; i < colors.length; i++) expr.push(i * step, colors[i]);
    return expr;
  }

  function initMap () {
    const b = state.info.network.bounds;
    map = new maplibregl.Map({
      container: 'map',
      bounds: [[b[0], b[1]], [b[2], b[3]]],
      fitBoundsOptions: {padding: 40},
      attributionControl: {compact: true},
      style: {
        version: 8,
        sources: {
          basemap: {
            type: 'raster',
            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
            tileSize: 256,
            maxzoom: 19,
            attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          }
        },
        layers: [
          {id: 'background', type: 'background', paint: {'background-color': '#eef1f4'}},
          {id: 'basemap', type: 'raster', source: 'basemap', paint: {'raster-opacity': 0.9, 'raster-saturation': -0.6}}
        ]
      }
    });
    map.addControl(new maplibregl.NavigationControl({showCompass: false}), 'top-right');
    map.on('load', () => {
      map.addSource('network', {type: 'geojson', data: {type: 'FeatureCollection', features: []}});
      map.addSource('walkshed', {type: 'geojson', data: {type: 'FeatureCollection', features: []}});
      map.addSource('snap', {type: 'geojson', data: {type: 'FeatureCollection', features: []}});
      // Edges that are not paths for this traveller at all (e.g. roads for a wheelchair user): background only.
      map.addLayer({
        id: 'network-other', type: 'line', source: 'network',
        filter: ['!', ['to-boolean', ['get', 'layer']]],
        paint: {'line-color': OTHER, 'line-width': ['interpolate', ['linear'], ['zoom'], 14, 0.5, 19, 1.5]}
      });
      map.addLayer({
        id: 'network-usable', type: 'line', source: 'network',
        filter: ['all', ['to-boolean', ['get', 'layer']], ['any', ['get', 'forward'], ['get', 'backward']]],
        paint: {'line-color': UNREACHED, 'line-width': ['interpolate', ['linear'], ['zoom'], 14, 0.8, 19, 2.5]}
      });
      map.addLayer({
        // Paths this traveller could use, but not with the current limits: too steep, no curb ramps.
        id: 'network-unusable', type: 'line', source: 'network',
        filter: ['all', ['to-boolean', ['get', 'layer']], ['!', ['get', 'forward']], ['!', ['get', 'backward']]],
        paint: {
          'line-color': BARRIER,
          'line-width': ['interpolate', ['linear'], ['zoom'], 14, 1, 19, 3],
          'line-dasharray': [2, 1.5]
        }
      });
      map.addLayer({
        id: 'walkshed', type: 'line', source: 'walkshed',
        layout: {'line-cap': 'round', 'line-join': 'round'},
        paint: {
          'line-color': bandExpression(),
          'line-width': ['interpolate', ['linear'], ['zoom'], 14, 2.5, 19, 7]
        }
      });
      map.addLayer({
        id: 'snap-line', type: 'line', source: 'snap', filter: ['==', ['geometry-type'], 'LineString'],
        paint: {'line-color': INK, 'line-width': 1.5, 'line-dasharray': [1.5, 1.5]}
      });
      map.addLayer({
        id: 'snap-point', type: 'circle', source: 'snap', filter: ['==', ['geometry-type'], 'Point'],
        paint: {'circle-radius': 4.5, 'circle-color': '#ffffff', 'circle-stroke-color': INK, 'circle-stroke-width': 2}
      });
      map.on('click', (e) => {
        state.origin = {lon: e.lngLat.lng, lat: e.lngLat.lat};
        placeOrigin();
        runWalkshed();
      });
      map.getCanvas().style.cursor = 'crosshair';
      loadNetwork();
      if (state.origin) { placeOrigin(); runWalkshed(); }
    });
  }

  function placeOrigin () {
    showSnap(null); // until the new result arrives
    const at = [state.origin.lon, state.origin.lat];
    if (originMarker) { originMarker.setLngLat(at); return; }
    const el = document.createElement('div');
    el.className = 'origin';
    el.setAttribute('aria-label', 'Starting point');
    originMarker = new maplibregl.Marker({element: el}).setLngLat(at).addTo(map);
  }

  // The walk starts at the nearest point on the network, not at the marker: show that point and the way to it.
  function showSnap (origin) {
    const features = [];
    if (origin) {
      const from = [origin.lon, origin.lat], to = [origin.snappedLon, origin.snappedLat];
      features.push({type: 'Feature', properties: {}, geometry: {type: 'LineString', coordinates: [from, to]}});
      features.push({type: 'Feature', properties: {}, geometry: {type: 'Point', coordinates: to}});
    }
    map.getSource('snap').setData({type: 'FeatureCollection', features});
  }

  // ---------------------------------------------------------------------------------------------- Requests

  async function fetchJson (url, options) {
    const res = await fetch(url, options);
    const body = await res.json().catch(() => ({error: 'The server sent an unreadable response (' + res.status + ').'}));
    if (!res.ok || body.error) throw new Error(body.error || ('Request failed (' + res.status + ')'));
    return body;
  }

  async function loadNetwork () {
    const n = ++networkRequest;
    const q = new URLSearchParams({profile: state.profileKey, parameters: JSON.stringify(state.params)});
    try {
      const fc = await fetchJson('api/network?' + q);
      if (n !== networkRequest) return;
      map.getSource('network').setData(fc);
    } catch (e) {
      setResult(e.message, true);
    }
  }

  async function runWalkshed () {
    if (!state.origin || !map) return;
    writeHash();
    const n = ++walkshedRequest;
    try {
      const body = await fetchJson('api/walkshed', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          profile: state.profileKey, parameters: state.params, walkSpeed: WALK_SPEED,
          maxMinutes: state.maxMinutes, lat: state.origin.lat, lon: state.origin.lon
        })
      });
      if (n !== walkshedRequest) return;
      map.getSource('walkshed').setData(body.walkshed);
      showSnap(body.origin);
      const p = body.provenance;
      $('run-id').textContent = short(p.runSpecId);
      $('run-id').dataset.full = p.runSpecId;
      $('profile-id').textContent = short(p.profileId);
      $('profile-id').dataset.full = p.profileId;
      if (body.stats.reachableMeters === 0) {
        setResult(body.origin.layer
          ? 'Nothing is reachable from here: the nearest path can\'t be used by this traveller.'
          : 'Nothing is reachable from here: the nearest edge isn\'t a path this traveller can use. Try a point on a sidewalk.', true);
      } else {
        setResult(`<strong>${formatDistance(body.stats.reachableMeters)}</strong> of paths reachable within ${state.maxCost} seconds (${formatMinutes(state.maxMinutes)} min).`);
      }
    } catch (e) {
      if (n === walkshedRequest) setResult(e.message, true);
    }
  }

  // Recompute after changes, at most every 250 ms while a slider is dragged.
  let timer;
  function changed () {
    clearTimeout(timer);
    timer = setTimeout(() => {
      writeHash();
      loadNetwork();
      runWalkshed();
    }, 250);
  }

  // ---------------------------------------------------------------------------------------------- Startup

  async function start () {
    readHash();
    try {
      state.info = await fetchJson('api/info');
    } catch (e) {
      $('dataset-summary').textContent = 'Could not reach the demo server: ' + e.message;
      return;
    }
    const net = state.info.network;
    $('dataset-name').textContent = net.name;
    $('dataset-summary').textContent = net.edges.toLocaleString() + ' OpenSidewalks edges';
    $('network-id').textContent = short(net.inputMd5);
    $('network-id').dataset.full = net.inputMd5;
    $('r5-version').textContent = state.info.r5.version;
    if (state.info.profiles.filter((p) => !p.error).length === 0) {
      setResult('No usable profiles found. Add profile JSON files to the profiles folder and reload.', true);
      return;
    }
    renderProfiles();
    renderParameters();
    renderTrip();
    renderLegend();
    $('profile').addEventListener('change', (e) => {
      state.profileKey = e.target.value;
      state.params = {}; // each profile starts from its own defaults
      renderParameters();
      changed();
    });
    $('show-unusable').addEventListener('change', (e) => {
      map.setLayoutProperty('network-unusable', 'visibility', e.target.checked ? 'visible' : 'none');
    });
    document.querySelectorAll('button.id').forEach((b) => b.addEventListener('click', async () => {
      if (!b.dataset.full) return;
      try {
        await navigator.clipboard.writeText(b.dataset.full);
        b.title = 'Copied ' + b.dataset.full;
      } catch (e) { b.title = b.dataset.full; }
    }));
    initMap();
  }

  start();
})();
