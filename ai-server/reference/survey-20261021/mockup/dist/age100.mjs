// Optional matching metadata, deliberately separate from the 100 scored responses.
export const ageVersion='SIS-AGE-1';
export const ageNumber=v=>Number.isInteger(v)&&v>=19&&v<=100?v:null;
export function cleanAge(p){return {age:ageNumber(p?.age),min:ageNumber(p?.min),max:ageNumber(p?.max)};}
export function ageDecision(mine,theirs){const a=cleanAge(mine),b=cleanAge(theirs);if(a.min===null||a.max===null||a.min>a.max||b.age===null)return 'unresolved';return b.age<a.min||b.age>a.max?'excluded':'accepted';}
